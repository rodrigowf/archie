/**
 * Fakes for the voice controller tests: a recording port, a scriptable transport, a cue
 * recorder, a manual clock and a network source. No audio, no sockets.
 */
import type { ClientMessage, ServerFrame } from '@/protocol';
import { VoiceController, type VoiceControllerDeps } from '../core/VoiceController';
import type {
  Clock,
  ConnectionType,
  CuePlayer,
  NetworkSource,
  OneShotCue,
  TransportEvents,
  TransportOptions,
  VoicePort,
  VoiceTransport,
} from '../core/types';

export class ManualClock implements Clock {
  t = 0;
  private timers: { id: number; at: number; fn: () => void }[] = [];
  private next = 1;
  now(): number {
    return this.t;
  }
  setTimeout(fn: () => void, ms: number): unknown {
    const id = this.next++;
    this.timers.push({ id, at: this.t + ms, fn });
    return id;
  }
  clearTimeout(h: unknown): void {
    this.timers = this.timers.filter((x) => x.id !== h);
  }
  advance(ms: number): void {
    const end = this.t + ms;
    for (;;) {
      this.timers.sort((a, b) => a.at - b.at);
      const first = this.timers[0];
      if (!first || first.at > end) break;
      this.timers.shift();
      this.t = first.at;
      first.fn();
    }
    this.t = end;
  }
  get pending(): number {
    return this.timers.length;
  }
}

export class FakePort implements VoicePort {
  localId = 'O1';
  sdk: string | null = 'O1';
  socketOpen = true;
  sent: ClientMessage[] = [];
  fed: Record<string, unknown>[] = [];
  localEnds = 0;
  startRequests = 0;
  voiceActive = false;
  /** Set by the harness: what the runtime would do on requestStart (send startMessage()). */
  onRequestStart: (() => void) | null = null;
  sdkId(): string | null {
    return this.sdk;
  }
  requestStart(): void {
    this.startRequests += 1;
    this.onRequestStart?.();
  }
  send(msg: ClientMessage): boolean {
    if (!this.socketOpen) return false;
    this.sent.push(msg);
    return true;
  }
  feedDataChannelEvent(e: Readonly<Record<string, unknown>>): void {
    this.fed.push(e as Record<string, unknown>);
  }
  voiceLocalEnd(): void {
    this.localEnds += 1;
  }
  conversationVoiceActive(): boolean {
    return this.voiceActive;
  }
  types(): string[] {
    return this.sent.map((m) => m.type);
  }
  /** Provider frames relayed as voice_event. */
  voiceEvents(): Record<string, unknown>[] {
    return this.sent.filter((m): m is { type: 'voice_event'; event: Record<string, unknown> } => m.type === 'voice_event').map((m) => m.event);
  }
  clear(): void {
    this.sent = [];
  }
}

export class FakeTransport implements VoiceTransport {
  started = false;
  closed = false;
  sentEvents: Record<string, unknown>[] = [];
  played: string[] = [];
  flushes = 0;
  micMuted = false;
  speakerMuted = false;
  dcOpen = false;
  isHealthy = true;
  startResult: Promise<void> = Promise.resolve();
  constructor(
    readonly kind: ConnectionType,
    readonly opts: TransportOptions,
  ) {}
  get events(): TransportEvents {
    return this.opts.events;
  }
  start(): Promise<void> {
    this.started = true;
    return this.startResult;
  }
  /** Simulate dc.onopen / capture running. */
  ready(): void {
    this.dcOpen = true;
    this.events.ready();
  }
  send(event: Record<string, unknown>): boolean {
    if (this.kind !== 'webrtc' || !this.dcOpen || this.closed) return false;
    this.sentEvents.push(event);
    this.events.outbound(event);
    return true;
  }
  playAudio(b64: string): void {
    this.played.push(b64);
  }
  flushPlayback(): void {
    this.flushes += 1;
  }
  setMicMuted(m: boolean): void {
    this.micMuted = m;
  }
  setSpeakerMuted(m: boolean): void {
    this.speakerMuted = m;
  }
  levels(): { mic: number; speaker: number } {
    return { mic: 0.2, speaker: 0.4 };
  }
  healthy(): boolean {
    return this.isHealthy && !this.closed && this.dcOpen;
  }
  close(): void {
    this.closed = true;
  }
}

export class FakeCues implements CuePlayer {
  log: string[] = [];
  looping = false;
  startLoop(): void {
    if (!this.looping) this.log.push('loop:start');
    this.looping = true;
  }
  stopLoop(): void {
    if (this.looping) this.log.push('loop:stop');
    this.looping = false;
  }
  play(cue: OneShotCue): void {
    this.log.push(cue);
  }
}

export class FakeNetwork implements NetworkSource {
  online = true;
  private fns: ((online: boolean) => void)[] = [];
  isOnline(): boolean {
    return this.online;
  }
  subscribe(fn: (online: boolean) => void): () => void {
    this.fns.push(fn);
    return () => {
      this.fns = this.fns.filter((f) => f !== fn);
    };
  }
  set(online: boolean): void {
    this.online = online;
    this.fns.forEach((f) => f(online));
  }
}

export const WEBRTC_INFO = {
  connection_type: 'webrtc',
  endpoint: 'https://api.openai.com/v1/realtime/calls?model=gpt-realtime',
  ephemeral_token: 'ek_test',
  audio_in_format: { sample_rate: 24000, encoding: 'pcm16' },
  audio_out_format: { sample_rate: 24000, encoding: 'pcm16' },
};
export const RELAY_INFO = {
  connection_type: 'websocket',
  endpoint: 'wss://dashscope.example/realtime',
  ephemeral_token: null,
  audio_in_format: { sample_rate: 16000, encoding: 'pcm16' },
  audio_out_format: { sample_rate: 24000, encoding: 'pcm16' },
  audio_relay: 'backend',
};
export const SESSION_UPDATE = { type: 'session.update', session: { instructions: 'You are Archie' } };

export interface Rig {
  c: VoiceController;
  port: FakePort;
  clock: ManualClock;
  cues: FakeCues;
  net: FakeNetwork;
  transports: FakeTransport[];
  /** The most recent transport. */
  t(): FakeTransport;
  frame(f: Record<string, unknown>): void;
  /** Answer the pending voice_start like the backend's initiator path. */
  started(over?: Record<string, unknown>): void;
  /** session_started + voice_owner_active for our own start. */
  answer(info?: Record<string, unknown>, over?: Record<string, unknown>): void;
  /** start → answer → transport ready (→ voice_status ready for the relay). */
  live(kind?: ConnectionType): FakeTransport;
  /** Socket drop as the runtime reports it. */
  drop(): void;
  /** Socket reopen as the runtime does: sends startMessage() (or a plain start). */
  reopen(): void;
}

export function rig(over: Partial<VoiceControllerDeps> = {}): Rig {
  const port = new FakePort();
  const clock = new ManualClock();
  const cues = new FakeCues();
  const net = new FakeNetwork();
  const transports: FakeTransport[] = [];
  const c = new VoiceController({
    port,
    clock,
    cues,
    network: net,
    createTransport: (kind, opts) => {
      const t = new FakeTransport(kind, opts);
      transports.push(t);
      return t;
    },
    unsupported: () => null,
    ...over,
  });
  const sendStart = (): void => {
    if (!port.socketOpen) return;
    const m = c.startMessage();
    port.sent.push(m ?? { type: 'start', local_id: port.localId });
  };
  port.onRequestStart = sendStart;
  const frame = (f: Record<string, unknown>): void => c.onFrame(f as unknown as ServerFrame);
  const r: Rig = {
    c,
    port,
    clock,
    cues,
    net,
    transports,
    t: () => {
      const t = transports[transports.length - 1];
      if (!t) throw new Error('no transport');
      return t;
    },
    frame,
    started(o = {}) {
      frame({ type: 'session_started', session_id: 'O1', voice: true, voice_initiator: true, voice_provider: 'openai', ...o });
    },
    answer(info = WEBRTC_INFO, o = {}) {
      r.started({ voice_connection_info: info, voice_session_update: SESSION_UPDATE, ...o });
      frame({ type: 'voice_owner_active', active: true, owner_local_id: 'O1' });
    },
    live(kind = 'webrtc') {
      c.start();
      r.answer(kind === 'webrtc' ? WEBRTC_INFO : RELAY_INFO, kind === 'webrtc' ? {} : { voice_provider: 'qwen' });
      const t = r.t();
      t.ready();
      if (kind === 'websocket') frame({ type: 'voice_event', event: { type: 'voice_status', status: 'ready' } });
      return t;
    },
    drop() {
      port.socketOpen = false;
      c.onSocketClosed();
    },
    reopen() {
      port.socketOpen = true;
      sendStart();
    },
  };
  return r;
}

export async function flushPromises(): Promise<void> {
  for (let i = 0; i < 5; i++) await Promise.resolve();
}
