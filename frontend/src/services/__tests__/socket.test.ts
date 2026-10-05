/** ArchieSocket (T-1..T-3) and the Reconnector in isolation. */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ArchieSocket, backoffPolicy, CHAT_WS_PATH, configureServices, legacyPolicy, Reconnector, setDefaultReconnectPolicy, wsUrl } from '@/services';
import { FakeWebSocket, setupServices, teardownServices, type Harness } from './fakes';

let h: Harness;
beforeEach(() => {
  h = setupServices();
});
afterEach(() => {
  teardownServices();
  vi.useRealTimers();
});

describe('ArchieSocket', () => {
  it('decodes binary and text frames, drops malformed ones, sends text only', () => {
    const frames: string[] = [];
    const closes: number[] = [];
    const s = new ArchieSocket(CHAT_WS_PATH, { onOpen: () => frames.push('open'), onFrame: (f) => frames.push(f.type), onClose: (i) => closes.push(i.code) });
    expect(s.send({ type: 'interrupt' })).toBe(false); // not connected
    s.connect();
    const ws = FakeWebSocket.last();
    expect(s.isConnecting).toBe(true);
    ws.open();
    expect(s.isOpen).toBe(true);
    ws.emit({ type: 'status', status: 'idle' });
    ws.emit({ type: 'text_delta', text: 'é漢😀' });
    ws.emitText('{"type":"session_stopped"}');
    ws.emitText('not json');
    ws.emitText('{"no":"type"}');
    ws.onmessage?.({ data: 42 });
    expect(frames).toEqual(['open', 'status', 'text_delta', 'session_stopped']);
    expect(s.send({ type: 'interrupt' })).toBe(true);
    expect(ws.sent).toEqual(['{"type":"interrupt"}']);
    ws.drop(1006);
    expect(closes).toEqual([1006]);
    expect(s.isOpen).toBe(false);
  });

  it('an upgrade with no answer is abandoned after 10 s and reported as an abnormal close (T-16)', () => {
    vi.useFakeTimers();
    const events: string[] = [];
    const s = new ArchieSocket(CHAT_WS_PATH, { onOpen: () => events.push('open'), onFrame: () => undefined, onClose: (i) => events.push(`close ${i.code} ${i.reason}`) });
    s.connect();
    const stuck = FakeWebSocket.last();
    vi.advanceTimersByTime(9_999);
    expect(events).toEqual([]);
    vi.advanceTimersByTime(1);
    expect(events).toEqual(['close 1006 handshake timeout']);
    expect(stuck.closedByClient).toBe(true);
    expect(s.isConnecting).toBe(false);
    // The next attempt opens normally and its timer never fires against the open socket.
    s.connect();
    FakeWebSocket.last().open();
    vi.advanceTimersByTime(20_000);
    expect(events).toEqual(['close 1006 handshake timeout', 'open']);
    expect(s.isOpen).toBe(true);
  });

  it('close() sends nothing and reports no close event', () => {
    const closes: number[] = [];
    const s = new ArchieSocket(CHAT_WS_PATH, { onOpen: () => undefined, onFrame: () => undefined, onClose: (i) => closes.push(i.code) });
    s.connect();
    const ws = FakeWebSocket.last();
    ws.open();
    s.close();
    ws.drop();
    expect(ws.sent).toEqual([]);
    expect(ws.closedByClient).toBe(true);
    expect(closes).toEqual([]);
  });

  it('stale sockets are ignored after reconnect; a throwing send returns false', () => {
    const frames: string[] = [];
    const s = new ArchieSocket(CHAT_WS_PATH, { onOpen: () => frames.push('open'), onFrame: (f) => frames.push(f.type), onClose: () => frames.push('close') });
    s.connect();
    const old = FakeWebSocket.last();
    const oldOnMessage = old.onmessage;
    s.connect();
    const cur = FakeWebSocket.last();
    oldOnMessage?.({ data: '{"type":"status","status":"x"}' });
    expect(frames).toEqual([]);
    cur.open();
    cur.send = () => {
      throw new Error('boom');
    };
    expect(s.send({ type: 'interrupt' })).toBe(false);
  });

  it('builds ws/wss URLs from the base', () => {
    configureServices({ baseUrl: 'https://192.168.0.200' });
    expect(wsUrl('/api/sessions/chat')).toBe('wss://192.168.0.200/api/sessions/chat');
    configureServices({ baseUrl: '' });
    expect(wsUrl('/x')).toBe(`ws://${location.host}/x`);
  });
});

describe('Reconnector', () => {
  function target() {
    const t = { open: false, connecting: false, connects: 0, resyncs: 0, gaveUp: 0 };
    return {
      t,
      r: {
        isOpen: () => t.open,
        isConnecting: () => t.connecting,
        connect: () => {
          t.connects++;
        },
        resync: () => {
          t.resyncs++;
        },
        gaveUp: () => {
          t.gaveUp++;
        },
      },
    };
  }

  it('backoff policy: jitter ±20 %, cap 15 s', () => {
    expect(backoffPolicy.delayMs(0, () => 0)).toBe(800);
    expect(backoffPolicy.delayMs(0, () => 0.999999)).toBe(1200);
    expect(backoffPolicy.delayMs(3, () => 0.5)).toBe(8000);
    expect(backoffPolicy.delayMs(30, () => 0.5)).toBe(15000);
    expect(legacyPolicy.delayMs(9, Math.random)).toBe(2000);
    expect(legacyPolicy.delayMs(10, Math.random)).toBeNull();
  });

  it('gives up per policy; reconnectNow resets; visible with an open socket resyncs', () => {
    vi.useFakeTimers();
    const { t, r } = target();
    const rc = new Reconnector(r, legacyPolicy);
    for (let i = 0; i < 11; i++) {
      rc.scheduleReconnect();
      vi.advanceTimersByTime(2000);
    }
    expect(t.connects).toBe(10);
    expect(t.gaveUp).toBe(1);
    rc.reconnectNow();
    expect(t.connects).toBe(11);
    expect(rc.attempt).toBe(0);
    t.open = true;
    h.visibility.set(true);
    h.visibility.set(false);
    expect(t.resyncs).toBe(1);
    t.open = false;
    t.connecting = true;
    h.visibility.set(false);
    expect(t.connects).toBe(11); // already connecting
    rc.stop();
    rc.scheduleReconnect();
    rc.reconnectNow();
    h.visibility.set(false);
    expect(t.connects).toBe(11);
  });

  it('a timer that fires while hidden does not connect', () => {
    vi.useFakeTimers();
    const { t, r } = target();
    const rc = new Reconnector(r);
    rc.scheduleReconnect();
    expect(rc.pending).toBe(true);
    h.visibility.hidden = true; // hidden without an event (e.g. bfcache)
    vi.advanceTimersByTime(5000);
    expect(t.connects).toBe(0);
    rc.stop();
  });

  it('the default policy can be swapped', () => {
    vi.useFakeTimers();
    setDefaultReconnectPolicy(legacyPolicy);
    const { t, r } = target();
    const rc = new Reconnector(r);
    rc.scheduleReconnect();
    vi.advanceTimersByTime(1999);
    expect(t.connects).toBe(0);
    vi.advanceTimersByTime(1);
    expect(t.connects).toBe(1);
    rc.stop();
    setDefaultReconnectPolicy(null);
  });
});
