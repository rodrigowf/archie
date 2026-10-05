/**
 * P-2 (plan/20): a network drop does not end the call quietly. From the moment the link is lost:
 * Reconnecting… with an elapsed timer and a repeating cue; automatic re-arm (V-8); then either a
 * distinct "reconnected" cue, or after the 30 s budget a failure state, a failure cue and a
 * manual Reconnect.
 */
import { describe, expect, it } from 'vitest';
import { LINK_RETRY_BUDGET_MS, RESTORED_DISPLAY_MS, TRANSPORT_RETRY_MS } from '../core/VoiceController';
import { rig } from './harness';

describe('P-2: socket drop on an established call', () => {
  it('the moment the socket drops: Reconnecting, timer anchored at the drop, the cue loop starts at once', () => {
    const r = rig();
    const t = r.live();
    r.clock.advance(5_000);
    r.drop();
    expect(r.c.snapshot).toMatchObject({ link: 'lost', linkLostAt: 5_000, linkSource: 'socket' });
    expect(r.cues.log).toEqual(['loop:start']);
    expect(t.closed).toBe(true); // the server ended voice for the dropped socket (G-31)
    expect(r.port.localEnds).toBe(0); // not an end: the call continues after the re-arm
  });

  it('V-8: the reopened socket re-arms with voice_start, never a plain start, and never stop', () => {
    const r = rig();
    r.live();
    r.drop();
    r.port.clear();
    r.clock.advance(2_000);
    r.reopen();
    expect(r.port.sent).toEqual([{ type: 'voice_start', local_id: 'O1', resume_sdk_id: 'O1' }]);
  });

  it('recovery: new transport ready → Reconnected (rising cue, "back after"), then Listening after 2.5 s', () => {
    const r = rig();
    r.live();
    r.clock.advance(1_000);
    r.drop();
    r.clock.advance(9_000);
    r.reopen();
    r.answer();
    expect(r.c.snapshot.link).toBe('lost');
    r.t().ready();
    expect(r.c.snapshot).toMatchObject({ link: 'restored', linkRecoveredMs: 9_000, status: 'active' });
    expect(r.cues.log).toEqual(['loop:start', 'loop:stop', 'reconnected']);
    r.clock.advance(RESTORED_DISPLAY_MS - 1);
    expect(r.c.snapshot.link).toBe('restored');
    r.clock.advance(1);
    expect(r.c.snapshot).toMatchObject({ link: 'ok', status: 'active', linkLostAt: null });
  });

  it('the budget: still Reconnecting at 29.999 s; at 30 s Couldn\'t reconnect + falling cue, nothing left armed', () => {
    const r = rig();
    r.live();
    r.drop();
    r.clock.advance(LINK_RETRY_BUDGET_MS - 1);
    expect(r.c.snapshot.link).toBe('lost');
    r.clock.advance(1);
    expect(r.c.snapshot).toMatchObject({ status: 'error', link: 'failed' });
    expect(r.c.snapshot.error?.message).toMatch(/Couldn.t reconnect/);
    expect(r.cues.log).toEqual(['loop:start', 'loop:stop', 'failed']);
    expect(r.port.localEnds).toBe(1); // running voice tools finish (VT-3)
    expect(r.c.startMessage()).toBeNull(); // the reopened socket sends a plain start
  });

  it('the budget counts from the drop even across failed re-arm attempts', () => {
    const r = rig();
    r.live();
    r.drop();
    r.clock.advance(10_000);
    r.reopen();
    r.frame({ type: 'error', error: 'voice_restart_failed', detail: 'relay boot failed' });
    expect(r.c.snapshot.link).toBe('lost'); // not an error: retrying
    r.port.clear();
    r.clock.advance(TRANSPORT_RETRY_MS);
    expect(r.port.types()).toEqual(['voice_start']); // retried on the open socket
    r.clock.advance(LINK_RETRY_BUDGET_MS - 10_000 - TRANSPORT_RETRY_MS);
    expect(r.c.snapshot.link).toBe('failed');
  });

  it('budget exhausted with the socket open ends any voice our re-arm started (explicit voice_stop)', () => {
    const r = rig();
    r.live();
    r.drop();
    r.reopen();
    r.port.clear();
    r.clock.advance(LINK_RETRY_BUDGET_MS);
    expect(r.port.types()).toContain('voice_stop');
  });

  it('manual Reconnect: a fresh budget, the cue loop and the timer again, then recovery', () => {
    const r = rig();
    r.live();
    r.drop();
    r.clock.advance(LINK_RETRY_BUDGET_MS);
    r.cues.log = [];
    r.port.socketOpen = true;
    r.port.clear();
    r.clock.advance(3_000);
    r.c.reconnect();
    expect(r.c.snapshot).toMatchObject({ status: 'connecting', link: 'lost', linkLostAt: 33_000 });
    expect(r.cues.log).toEqual(['loop:start']);
    expect(r.port.types()).toEqual(['voice_start']);
    r.answer();
    r.t().ready();
    expect(r.c.snapshot).toMatchObject({ link: 'restored', status: 'active' });
    expect(r.cues.log).toEqual(['loop:start', 'loop:stop', 'reconnected']);
  });

  it('End while reconnecting: off at once, cues stop, voice_local_end, no re-arm', () => {
    const r = rig();
    r.live();
    r.drop();
    r.c.stop();
    expect(r.c.snapshot).toMatchObject({ status: 'off', link: 'ok' });
    expect(r.cues.log).toEqual(['loop:start', 'loop:stop']);
    expect(r.port.localEnds).toBe(1);
    expect(r.c.startMessage()).toBeNull();
  });

  it('a voice_ended arriving while re-arming is stale and ignored', () => {
    const r = rig();
    r.live();
    r.drop();
    r.reopen();
    r.frame({ type: 'voice_ended', reason: 'client_disconnect' });
    expect(r.c.snapshot.link).toBe('lost');
  });

  it('a drop while still bringing the call up is not P-2: no cue, stays Connecting, re-sends voice_start', () => {
    const r = rig();
    r.c.start();
    r.answer();
    r.drop();
    expect(r.cues.log).toEqual([]);
    expect(r.c.snapshot).toMatchObject({ status: 'connecting', link: 'ok' });
    r.port.clear();
    r.reopen();
    expect(r.port.types()).toEqual(['voice_start']);
  });

  it('a drop while Ending finishes the end locally', () => {
    const r = rig();
    r.live();
    r.c.stop();
    r.drop();
    expect(r.c.snapshot.status).toBe('off');
    expect(r.port.localEnds).toBe(1);
  });
});

describe('P-2: transport and network drops', () => {
  it('WebRTC ICE disconnected → Reconnecting with the transport kept; connected again → Reconnected', () => {
    const r = rig();
    const t = r.live();
    r.clock.advance(2_000);
    t.events.linkDown();
    expect(r.c.snapshot).toMatchObject({ link: 'lost', linkSource: 'transport', linkLostAt: 2_000 });
    expect(t.closed).toBe(false);
    expect(r.c.levels()).toEqual({ mic: 0, speaker: 0 });
    r.clock.advance(4_000);
    t.events.linkUp();
    expect(r.c.snapshot).toMatchObject({ link: 'restored', linkRecoveredMs: 4_000 });
    expect(r.cues.log).toEqual(['loop:start', 'loop:stop', 'reconnected']);
  });

  it('a dead transport on an open socket: re-arm after 2 s on the same socket, rebuild, Reconnected', () => {
    const r = rig();
    const t = r.live();
    r.port.clear();
    t.events.failed('The voice connection was lost');
    expect(t.closed).toBe(true);
    expect(r.c.snapshot.link).toBe('lost');
    expect(r.port.sent).toEqual([]);
    r.clock.advance(TRANSPORT_RETRY_MS);
    expect(r.port.types()).toEqual(['voice_start']);
    r.answer();
    expect(r.transports).toHaveLength(2);
    r.t().ready();
    expect(r.c.snapshot).toMatchObject({ link: 'restored', status: 'active' });
  });

  it('browser offline → Reconnecting at once; online with a healthy transport → Reconnected', () => {
    const r = rig();
    r.live();
    r.net.set(false);
    expect(r.c.snapshot).toMatchObject({ link: 'lost', linkSource: 'network' });
    expect(r.cues.log).toEqual(['loop:start']);
    r.net.set(true);
    expect(r.c.snapshot.link).toBe('restored');
  });

  it('offline then the socket drops: one outage, one timer, one cue loop', () => {
    const r = rig();
    r.live();
    r.clock.advance(1_000);
    r.net.set(false);
    r.clock.advance(3_000);
    r.drop();
    expect(r.c.snapshot).toMatchObject({ link: 'lost', linkLostAt: 1_000 });
    expect(r.cues.log).toEqual(['loop:start']);
    r.clock.advance(LINK_RETRY_BUDGET_MS - 3_000);
    expect(r.c.snapshot.link).toBe('failed');
  });

  it('the server relay reconnecting (voice_status reconnecting) shows Reconnecting without the client budget; ready restores', () => {
    const r = rig();
    const t = r.live('websocket');
    r.frame({ type: 'voice_event', event: { type: 'voice_status', status: 'reconnecting' } });
    expect(r.c.snapshot).toMatchObject({ link: 'lost', linkSource: 'provider' });
    expect(t.closed).toBe(false);
    r.port.clear();
    t.events.audioChunk('AAAA'); // paused while the relay reconnects
    expect(r.port.sent).toEqual([]);
    r.clock.advance(LINK_RETRY_BUDGET_MS * 2);
    expect(r.c.snapshot.link).toBe('lost'); // the server decides (voice_error / voice_ended)
    r.frame({ type: 'voice_event', event: { type: 'voice_status', status: 'ready' } });
    expect(r.c.snapshot.link).toBe('restored');
    t.events.audioChunk('BBBB');
    expect(r.port.sent).toEqual([{ type: 'voice_audio_in', audio: 'BBBB' }]);
  });

  it('G-35: Qwen sends no ready after a relay reconnect; the next transcript or audio clears it', () => {
    const r = rig();
    const t = r.live('websocket');
    r.frame({ type: 'voice_event', event: { type: 'voice_status', status: 'reconnecting' } });
    r.frame({ type: 'voice_audio_out', audio: 'UENN' });
    expect(r.c.snapshot.link).toBe('restored');
    r.port.clear();
    t.events.audioChunk('CCCC');
    expect(r.port.sent).toEqual([{ type: 'voice_audio_in', audio: 'CCCC' }]);
  });

  it('a socket drop on top of a relay reconnect switches to the client budget', () => {
    const r = rig();
    r.live('websocket');
    r.frame({ type: 'voice_event', event: { type: 'voice_status', status: 'reconnecting' } });
    r.drop();
    expect(r.c.snapshot.linkSource).toBe('socket');
    r.clock.advance(LINK_RETRY_BUDGET_MS);
    expect(r.c.snapshot.link).toBe('failed');
  });
});
