/** Transports with fakes (spec 13 W-12 DoD: data channel, AudioContext). */
import { describe, expect, it, vi } from 'vitest';
import type { ConnectionInfo, TransportEvents } from '../core/types';
import { DEFAULT_CALLS_URL, OAI_EVENTS_CHANNEL, WebRtcTransport, type AudioElementLike, type WebRtcEnv } from '../transports/webrtc';
import { WsRelayTransport } from '../transports/wsRelay';
import { micErrorText } from '../transports/shared';
import { FakeAudioContext, FakeDataChannel, FakePeer, FakeWorkletNode, fakeStream } from './fakeAudio';
import { flushPromises } from './harness';

function recorder(): TransportEvents & { log: unknown[][] } {
  const log: unknown[][] = [];
  const rec = (name: string) => (...args: unknown[]) => {
    log.push([name, ...args]);
  };
  return {
    log,
    ready: rec('ready'),
    providerEvent: rec('providerEvent'),
    outbound: rec('outbound'),
    audioChunk: rec('audioChunk'),
    linkDown: rec('linkDown'),
    linkUp: rec('linkUp'),
    failed: rec('failed'),
    recordingChunk: rec('recordingChunk'),
    recordingEnd: rec('recordingEnd'),
  };
}

const RTC_INFO: ConnectionInfo = { connectionType: 'webrtc', endpoint: 'https://calls.test/v1?model=m', token: 'ek_1', inRate: 24000, outRate: 24000 };

function rtcEnv(over: Partial<WebRtcEnv> = {}) {
  const ctx = new FakeAudioContext();
  const mic = fakeStream();
  const el: AudioElementLike & { played: number } = {
    muted: false,
    srcObject: null,
    played: 0,
    play() {
      this.played += 1;
      return Promise.resolve();
    },
  };
  const fetch = vi.fn((_url: string, _init: { method: string; headers: Record<string, string>; body: string }) =>
    Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('v=0 answer') }),
  );
  const env: WebRtcEnv = {
    RTCPeerConnection: FakePeer,
    media: { getUserMedia: vi.fn(() => Promise.resolve(mic)) },
    fetch,
    audioElement: () => el,
    audioContext: () => ctx,
    WorkletNode: null,
    ...over,
  };
  return { env, ctx, mic, el, fetch };
}

describe('WebRTC transport (OpenAI)', () => {
  it('SDP offer to connection_info.endpoint with the ephemeral bearer token; data channel "oai-events"', async () => {
    const { env, fetch, mic } = rtcEnv();
    const ev = recorder();
    const t = new WebRtcTransport(RTC_INFO, ev, env, false);
    await t.start();
    const peer = FakePeer.last as FakePeer;
    expect(peer.channel?.label).toBe(OAI_EVENTS_CHANNEL);
    expect(fetch).toHaveBeenCalledWith('https://calls.test/v1?model=m', {
      method: 'POST',
      headers: { Authorization: 'Bearer ek_1', 'Content-Type': 'application/sdp' },
      body: 'v=0 offer',
    });
    expect(peer.remote).toEqual({ type: 'answer', sdp: 'v=0 answer' });
    expect(peer.tracks).toEqual([mic.track]);
    expect(env.media.getUserMedia).toHaveBeenCalledWith({ audio: { echoCancellation: true, noiseSuppression: true, sampleRate: 24000 } });
  });

  it('falls back to the default calls URL', async () => {
    const { env, fetch } = rtcEnv();
    await new WebRtcTransport({ ...RTC_INFO, endpoint: null }, recorder(), env, false).start();
    expect(fetch.mock.calls[0]?.[0]).toBe(DEFAULT_CALLS_URL);
  });

  it('ready on dc open; inbound JSON → providerEvent; garbage ignored; send mirrors outbound', async () => {
    const { env } = rtcEnv();
    const ev = recorder();
    const t = new WebRtcTransport(RTC_INFO, ev, env, false);
    await t.start();
    const dc = (FakePeer.last as FakePeer).channel as FakeDataChannel;
    expect(t.send({ type: 'x' })).toBe(false); // not open yet
    dc.open();
    dc.onmessage?.({ data: '{"type":"response.created"}' });
    dc.onmessage?.({ data: 'not json' });
    expect(t.send({ type: 'session.update' })).toBe(true);
    expect(dc.sent).toEqual(['{"type":"session.update"}']);
    expect(ev.log).toEqual([['ready'], ['providerEvent', { type: 'response.created' }], ['outbound', { type: 'session.update' }]]);
    expect(t.healthy()).toBe(true);
  });

  it('remote track plays on the unlocked element; speaker mute sets element.muted', async () => {
    const { env, el } = rtcEnv();
    const t = new WebRtcTransport(RTC_INFO, recorder(), env, false);
    await t.start();
    const remote = fakeStream();
    (FakePeer.last as FakePeer).ontrack?.({ streams: [remote] });
    expect(el.srcObject).toBe(remote);
    expect(el.played).toBe(1);
    t.setSpeakerMuted(true);
    expect(el.muted).toBe(true);
  });

  it('P-2: ICE disconnected → linkDown, connected → linkUp; failed → failed once', async () => {
    const { env } = rtcEnv();
    const ev = recorder();
    const t = new WebRtcTransport(RTC_INFO, ev, env, false);
    await t.start();
    const peer = FakePeer.last as FakePeer;
    peer.setState('disconnected');
    peer.setState('disconnected');
    peer.setState('connected');
    peer.setState('failed');
    peer.setState('closed');
    expect(ev.log.map((l) => l[0])).toEqual(['linkDown', 'linkUp', 'failed']);
    expect(t.healthy()).toBe(false);
  });

  it('Safari 12 (no connectionState) uses iceConnectionState', async () => {
    const { env } = rtcEnv();
    const ev = recorder();
    await new WebRtcTransport(RTC_INFO, ev, env, false).start();
    const peer = FakePeer.last as FakePeer;
    peer.connectionState = undefined;
    peer.iceConnectionState = 'disconnected';
    peer.oniceconnectionstatechange?.();
    expect(ev.log.map((l) => l[0])).toEqual(['linkDown']);
  });

  it('an unexpected data-channel close is a dead transport; close() does not report it', async () => {
    const { env, mic } = rtcEnv();
    const ev = recorder();
    const t = new WebRtcTransport(RTC_INFO, ev, env, false);
    await t.start();
    ((FakePeer.last as FakePeer).channel as FakeDataChannel).onclose?.();
    expect(ev.log).toEqual([['failed', 'The voice connection closed']]);
    const ev2 = recorder();
    const t2 = new WebRtcTransport(RTC_INFO, ev2, rtcEnv().env, false);
    await t2.start();
    t2.close();
    expect(ev2.log).toEqual([]);
    t.close();
    expect(mic.track.stopped).toBe(true);
    expect((FakePeer.last as FakePeer).closed).toBe(true);
  });

  it('mic mute disables the track', async () => {
    const { env, mic } = rtcEnv();
    const t = new WebRtcTransport(RTC_INFO, recorder(), env, false);
    await t.start();
    t.setMicMuted(true);
    expect(mic.track.enabled).toBe(false);
  });

  it('failures carry human messages: no token, mic denied, SDP refused', async () => {
    await expect(new WebRtcTransport({ ...RTC_INFO, token: null }, recorder(), rtcEnv().env, false).start()).rejects.toThrow(/no session token/);
    const denied = Object.assign(new Error('x'), { name: 'NotAllowedError' });
    await expect(
      new WebRtcTransport(RTC_INFO, recorder(), rtcEnv({ media: { getUserMedia: () => Promise.reject(denied) } }).env, false).start(),
    ).rejects.toThrow(/Microphone access was denied/);
    const refused = rtcEnv({
      fetch: () => Promise.resolve({ ok: false, status: 401, text: () => Promise.resolve('expired token') }),
    });
    await expect(new WebRtcTransport(RTC_INFO, recorder(), refused.env, false).start()).rejects.toThrow(/refused the call \(401\): expired token/);
  });

  it('§7.8 recording uses the "pcm-capture" worklet on both channels (fixes W-2) and ends on close', async () => {
    FakeWorkletNode.instances = [];
    vi.useFakeTimers();
    try {
      const { env } = rtcEnv({ WorkletNode: FakeWorkletNode, workletUrl: '/next/pcm-capture-worklet.js' });
      const ev = recorder();
      const t = new WebRtcTransport(RTC_INFO, ev, env, true);
      await t.start();
      (FakePeer.last as FakePeer).ontrack?.({ streams: [fakeStream()] });
      await flushPromises();
      expect(FakeWorkletNode.instances.map((n) => n.name)).toEqual(['pcm-capture', 'pcm-capture']);
      expect(FakeWorkletNode.instances.every((n) => n.connections.length === 1)).toBe(true); // connected to a sink
      FakeWorkletNode.instances[0]?.post([1, 0, 2, 0]);
      vi.advanceTimersByTime(5_000);
      expect(ev.log).toContainEqual(['recordingChunk', 'user', 'AQACAA==']);
      t.close();
      expect(ev.log[ev.log.length - 1]).toEqual(['recordingEnd']);
    } finally {
      vi.useRealTimers();
    }
  });

  it('micErrorText maps getUserMedia errors', () => {
    expect(micErrorText({ name: 'NotFoundError' })).toBe('No microphone was found.');
    expect(micErrorText({ name: 'NotReadableError' })).toMatch(/in use/);
    expect(micErrorText(new Error('boom'))).toBe('boom');
    expect(micErrorText(null)).toBe('Could not open the microphone.');
  });
});

describe('WS relay transport (Qwen / Gemini)', () => {
  const RELAY: ConnectionInfo = { connectionType: 'websocket', endpoint: null, token: null, inRate: 16000, outRate: 24000 };

  function relay() {
    FakeWorkletNode.instances = [];
    const ctx = new FakeAudioContext();
    const mic = fakeStream();
    const ev = recorder();
    const t = new WsRelayTransport(RELAY, ev, {
      media: { getUserMedia: vi.fn(() => Promise.resolve(mic)) },
      audioContext: () => ctx,
      WorkletNode: FakeWorkletNode,
      workletUrl: '/pcm-capture-worklet.js',
    });
    return { t, ev, ctx, mic };
  }

  it('loads the worklet once, captures with "pcm-capture" at the input rate in 100 ms chunks, base64 to audioChunk', async () => {
    const { t, ev, ctx } = relay();
    await t.start();
    expect(ctx.modules).toEqual(['/pcm-capture-worklet.js']);
    const node = FakeWorkletNode.instances[0] as FakeWorkletNode;
    expect(node.name).toBe('pcm-capture');
    expect(node.opts.processorOptions).toEqual({ targetSampleRate: 16000, chunkMs: 100 });
    // mic → worklet → gain-0 sink → destination (runs without feedback)
    const sink = ctx.gains.find((g) => g.gain.value === 0);
    expect(sink?.connections).toEqual([ctx.destination]);
    node.post([0xff, 0x7f]);
    expect(ev.log).toEqual([['ready'], ['audioChunk', '/38=']]);
    expect(t.healthy()).toBe(true);
  });

  it('plays voice_audio_out through the gapless player; flush, mute and close', async () => {
    const { t, ctx, mic } = relay();
    await t.start();
    t.playAudio('AAAAAA=='); // 2 samples
    expect(ctx.sources).toHaveLength(1);
    t.flushPlayback();
    expect(ctx.sources[0]?.stopped).toBe(true);
    t.setSpeakerMuted(true);
    t.setMicMuted(true);
    expect(mic.track.enabled).toBe(false);
    t.close();
    expect(mic.track.stopped).toBe(true);
    expect(t.healthy()).toBe(false);
    t.playAudio('AAAAAA==');
    expect(ctx.sources).toHaveLength(1);
  });

  it('no AudioWorklet → start rejects (P-8 gating normally prevents reaching here)', async () => {
    const ctx = new FakeAudioContext();
    const t = new WsRelayTransport(RELAY, recorder(), {
      media: { getUserMedia: () => Promise.resolve(fakeStream()) },
      audioContext: () => ctx,
      WorkletNode: null,
    });
    await expect(t.start()).rejects.toThrow(/AudioWorklet/);
  });
});
