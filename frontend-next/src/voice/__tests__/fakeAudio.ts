/** Fake Web Audio / WebRTC / media for the transport, player, cue and recorder tests. */
import type { AnalyserNodeLike, AudioContextLike, AudioNodeLike, BufferSourceLike, GainNodeLike, OscillatorLike } from '../audio/context';
import type { WorkletNodeLike } from '../audio/capture/worklet';
import type { DataChannelLike, PeerConnectionLike } from '../transports/webrtc';

export class FakeNode implements AudioNodeLike {
  connections: unknown[] = [];
  disconnected = false;
  constructor(readonly kind: string) {}
  connect(dest: unknown): unknown {
    this.connections.push(dest);
    return dest;
  }
  disconnect(): void {
    this.disconnected = true;
  }
}

export class FakeParam {
  value = 1;
  events: [string, number, number][] = [];
  setValueAtTime(v: number, t: number): void {
    this.events.push(['set', v, t]);
  }
  linearRampToValueAtTime(v: number, t: number): void {
    this.events.push(['ramp', v, t]);
  }
}

export class FakeGain extends FakeNode implements GainNodeLike {
  gain = new FakeParam();
  constructor() {
    super('gain');
  }
}

export class FakeAnalyser extends FakeNode implements AnalyserNodeLike {
  fftSize = 256;
  /** Byte value for every sample (128 = silence). */
  sample = 128;
  constructor() {
    super('analyser');
  }
  get frequencyBinCount(): number {
    return this.fftSize / 2;
  }
  getByteTimeDomainData(arr: Uint8Array): void {
    arr.fill(this.sample);
  }
}

export class FakeBuffer {
  private readonly data: Float32Array;
  constructor(
    readonly length: number,
    readonly sampleRate: number,
  ) {
    this.data = new Float32Array(length);
  }
  get duration(): number {
    return this.length / this.sampleRate;
  }
  getChannelData(_ch = 0): Float32Array {
    return this.data;
  }
}

export class FakeSource extends FakeNode implements BufferSourceLike {
  buffer: FakeBuffer | null = null;
  onended: (() => void) | null = null;
  startedAt: number | null = null;
  stopped = false;
  constructor() {
    super('source');
  }
  start(when = 0): void {
    this.startedAt = when;
  }
  stop(): void {
    this.stopped = true;
  }
}

export class FakeOscillator extends FakeNode implements OscillatorLike {
  type = 'sine';
  frequency = new FakeParam();
  startAt: number | null = null;
  stopAt: number | null = null;
  constructor() {
    super('osc');
  }
  start(when = 0): void {
    this.startAt = when;
  }
  stop(when = 0): void {
    this.stopAt = when;
  }
}

export class FakeAudioContext implements AudioContextLike {
  currentTime = 0;
  sampleRate = 48000;
  destination = new FakeNode('destination');
  state = 'running';
  sources: FakeSource[] = [];
  oscillators: FakeOscillator[] = [];
  analysers: FakeAnalyser[] = [];
  gains: FakeGain[] = [];
  modules: string[] = [];
  streamSources: FakeNode[] = [];
  resumed = 0;
  audioWorklet = {
    addModule: (url: string): Promise<void> => {
      this.modules.push(url);
      return Promise.resolve();
    },
  };
  resume(): Promise<void> {
    this.resumed += 1;
    this.state = 'running';
    return Promise.resolve();
  }
  createGain(): FakeGain {
    const g = new FakeGain();
    this.gains.push(g);
    return g;
  }
  createAnalyser(): FakeAnalyser {
    const a = new FakeAnalyser();
    this.analysers.push(a);
    return a;
  }
  createBuffer(_ch: number, length: number, sampleRate: number): FakeBuffer {
    return new FakeBuffer(length, sampleRate);
  }
  createBufferSource(): FakeSource {
    const s = new FakeSource();
    this.sources.push(s);
    return s;
  }
  createOscillator(): FakeOscillator {
    const o = new FakeOscillator();
    this.oscillators.push(o);
    return o;
  }
  createMediaStreamSource(): FakeNode {
    const n = new FakeNode('streamSource');
    this.streamSources.push(n);
    return n;
  }
}

export class FakeWorkletNode extends FakeNode implements WorkletNodeLike {
  static instances: FakeWorkletNode[] = [];
  port: { onmessage: ((e: { data: unknown }) => void) | null } = { onmessage: null };
  constructor(
    readonly ctx: unknown,
    readonly name: string,
    readonly opts: { processorOptions: { targetSampleRate: number; chunkMs: number } },
  ) {
    super('worklet');
    FakeWorkletNode.instances.push(this);
  }
  /** What the worklet posts (`{type:'pcm', buffer}`). */
  post(bytes: number[]): void {
    this.port.onmessage?.({ data: { type: 'pcm', buffer: new Uint8Array(bytes).buffer } });
  }
}

export class FakeTrack {
  enabled = true;
  stopped = false;
  stop(): void {
    this.stopped = true;
  }
}

export function fakeStream(): MediaStream & { track: FakeTrack } {
  const track = new FakeTrack();
  return {
    track,
    getTracks: () => [track],
    getAudioTracks: () => [track],
  } as unknown as MediaStream & { track: FakeTrack };
}

export class FakeDataChannel implements DataChannelLike {
  readyState = 'connecting';
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: unknown }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: ((e: unknown) => void) | null = null;
  sent: string[] = [];
  constructor(readonly label: string) {}
  send(data: string): void {
    this.sent.push(data);
  }
  close(): void {
    this.readyState = 'closed';
  }
  open(): void {
    this.readyState = 'open';
    this.onopen?.();
  }
}

export class FakePeer implements PeerConnectionLike {
  static last: FakePeer | null = null;
  connectionState: string | undefined = 'new';
  iceConnectionState: string | undefined = 'new';
  ontrack: ((e: { streams: readonly MediaStream[] }) => void) | null = null;
  onconnectionstatechange: (() => void) | null = null;
  oniceconnectionstatechange: (() => void) | null = null;
  tracks: unknown[] = [];
  channel: FakeDataChannel | null = null;
  remote: { type: string; sdp: string } | null = null;
  closed = false;
  constructor() {
    FakePeer.last = this;
  }
  addTrack(track: MediaStreamTrack): unknown {
    this.tracks.push(track);
    return {};
  }
  createDataChannel(label: string): FakeDataChannel {
    this.channel = new FakeDataChannel(label);
    return this.channel;
  }
  createOffer(): Promise<{ type: string; sdp: string }> {
    return Promise.resolve({ type: 'offer', sdp: 'v=0 offer' });
  }
  setLocalDescription(): Promise<void> {
    return Promise.resolve();
  }
  setRemoteDescription(d: { type: 'answer'; sdp: string }): Promise<void> {
    this.remote = d;
    return Promise.resolve();
  }
  close(): void {
    this.closed = true;
  }
  setState(s: string): void {
    this.connectionState = s;
    this.onconnectionstatechange?.();
  }
}
