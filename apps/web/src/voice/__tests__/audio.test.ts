/** PCM player, cues, meters, base64, capability gating, the worklet file. */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import type { ClientCapabilities } from '@/platform';
import { base64ToBytes, bytesToBase64, concatBytes, pcm16ToFloat32 } from '../audio/base64';
import { PCM_CAPTURE_PROCESSOR, workletUrl } from '../audio/capture/worklet';
import { FAILED_PATTERN, RECONNECTED_PATTERN, RECONNECTING_PATTERN, RECONNECTING_PERIOD_MS, WebAudioCuePlayer } from '../audio/cues';
import { analyserLevel, streamMeter, visualLevel } from '../audio/meters';
import { PcmPlayer } from '../audio/pcmPlayer';
import {
  parseConnectionInfo,
  transportForProvider,
  voiceUnsupportedReason,
  VOICE_NEEDS_HTTPS,
  VOICE_RELAY_UNSUPPORTED,
  VOICE_UNSUPPORTED,
  VOICE_WEBRTC_UNSUPPORTED,
} from '../core/support';
import { FakeAnalyser, FakeAudioContext, fakeStream } from './fakeAudio';
import { ManualClock } from './harness';

const HERE = dirname(fileURLToPath(import.meta.url));

describe('PcmPlayer (LOAD-BEARING inv02 F-26: gapless, flush on barge-in)', () => {
  const chunk = (samples: number): string => bytesToBase64(new Uint8Array(samples * 2));

  it('schedules chunks tail-to-tail on a running cursor', () => {
    const ctx = new FakeAudioContext();
    const p = new PcmPlayer(ctx, 24000);
    p.push(chunk(2400)); // 100 ms
    p.push(chunk(2400));
    p.push(chunk(1200));
    expect(ctx.sources.map((s) => s.startedAt)).toEqual([0, 0.1, 0.2]);
  });

  it('a chunk behind the clock starts now', () => {
    const ctx = new FakeAudioContext();
    const p = new PcmPlayer(ctx, 24000);
    p.push(chunk(2400));
    ctx.currentTime = 5;
    p.push(chunk(2400));
    expect(ctx.sources[1]?.startedAt).toBe(5);
  });

  it('flush stops every scheduled source including the playing one and resets the cursor', () => {
    const ctx = new FakeAudioContext();
    const p = new PcmPlayer(ctx, 24000);
    p.push(chunk(2400));
    p.push(chunk(2400));
    ctx.currentTime = 0.05;
    p.flush();
    expect(ctx.sources.every((s) => s.stopped)).toBe(true);
    expect(p.pending).toBe(0);
    p.push(chunk(2400));
    expect(ctx.sources[2]?.startedAt).toBe(0.05);
  });

  it('decodes PCM16 LE into the buffer; ended sources leave the set; mute is gain 0', () => {
    const ctx = new FakeAudioContext();
    const p = new PcmPlayer(ctx, 16000);
    p.push(bytesToBase64(new Uint8Array([0x00, 0x40, 0x00, 0xc0])));
    const src = ctx.sources[0];
    expect(Array.from(src?.buffer?.getChannelData(0) ?? [])).toEqual([0.5, -0.5]);
    src?.onended?.();
    expect(p.pending).toBe(0);
    p.setMuted(true);
    expect(ctx.gains[0]?.gain.value).toBe(0);
    expect(p.isMuted()).toBe(true);
    p.destroy();
    p.push(chunk(10));
    expect(ctx.sources).toHaveLength(1);
    expect(p.level()).toBe(0);
  });
});

describe('P-2 cues', () => {
  function cuePlayer() {
    const ctx = new FakeAudioContext();
    const clock = new ManualClock();
    const cues = new WebAudioCuePlayer(() => ctx, clock);
    return { ctx, clock, cues };
  }

  it('the reconnecting loop sounds immediately and every 2 s until stopped', () => {
    const { ctx, clock, cues } = cuePlayer();
    cues.startLoop();
    expect(ctx.oscillators).toHaveLength(RECONNECTING_PATTERN.length);
    cues.startLoop(); // idempotent
    clock.advance(RECONNECTING_PERIOD_MS * 3);
    expect(ctx.oscillators).toHaveLength(RECONNECTING_PATTERN.length * 4);
    cues.stopLoop();
    clock.advance(RECONNECTING_PERIOD_MS * 3);
    expect(ctx.oscillators).toHaveLength(RECONNECTING_PATTERN.length * 4);
    expect(cues.isLooping).toBe(false);
  });

  it('reconnected rises, failed falls, and they differ from the loop', () => {
    const { ctx, cues } = cuePlayer();
    cues.play('reconnected');
    const up = ctx.oscillators.map((o) => o.frequency.value);
    expect(up).toEqual(RECONNECTED_PATTERN.map((t) => t.freq));
    expect(up[1]).toBeGreaterThan(up[0] as number);
    ctx.oscillators = [];
    cues.play('failed');
    const down = ctx.oscillators.map((o) => o.frequency.value);
    expect(down).toEqual(FAILED_PATTERN.map((t) => t.freq));
    expect(down[2]).toBeLessThan(down[0] as number);
  });

  it('tones have soft envelopes, stop on time, and resume a suspended context', () => {
    const { ctx, cues } = cuePlayer();
    ctx.state = 'suspended';
    ctx.currentTime = 10;
    cues.play('reconnected');
    expect(ctx.resumed).toBe(1);
    const o = ctx.oscillators[0];
    expect(o?.startAt).toBeCloseTo(10.02);
    expect(o?.stopAt).toBeCloseTo(10.02 + 0.12 + 0.01);
    expect(ctx.gains[0]?.gain.events[0]).toEqual(['set', 0, o?.startAt]);
  });

  it('no audio context: cues are silent, never throw', () => {
    const cues = new WebAudioCuePlayer(() => null, new ManualClock());
    expect(() => {
      cues.startLoop();
      cues.play('failed');
      cues.stopLoop();
    }).not.toThrow();
  });
});

describe('meters and base64', () => {
  it('RMS from the analyser; visual mapping clamps to 0..1', () => {
    const a = new FakeAnalyser();
    expect(analyserLevel(a)).toBe(0);
    a.sample = 192;
    expect(analyserLevel(a)).toBeCloseTo(0.5);
    expect(visualLevel(0)).toBe(0);
    expect(visualLevel(1)).toBe(1);
    expect(visualLevel(0.04)).toBeCloseTo(0.4);
  });

  it('streamMeter reads a stream; silent without a context', () => {
    const ctx = new FakeAudioContext();
    const m = streamMeter(ctx, fakeStream());
    (ctx.analysers[0] as FakeAnalyser).sample = 255;
    expect(m.level()).toBeGreaterThan(0.9);
    m.close();
    expect(streamMeter(null, fakeStream()).level()).toBe(0);
  });

  it('base64 round-trips large buffers in steps', () => {
    const big = new Uint8Array(10_000).map((_, i) => i % 256);
    expect(Array.from(base64ToBytes(bytesToBase64(big)))).toEqual(Array.from(big));
    expect(Array.from(concatBytes([new Uint8Array([1]), new Uint8Array([2, 3])]))).toEqual([1, 2, 3]);
    expect(Array.from(pcm16ToFloat32(new Uint8Array([0xff, 0x7f])))).toEqual([0x7fff / 0x8000]);
  });
});

describe('capability gating (spec 13 §2.5, P-8)', () => {
  const caps = (over: Partial<ClientCapabilities> = {}): ClientCapabilities => ({
    secureContext: true,
    webAudio: true,
    audioWorklet: true,
    mediaRecorder: true,
    getUserMedia: true,
    webrtc: true,
    clipboardApi: true,
    resizeObserver: true,
    pointerEvents: true,
    touch: false,
    serviceWorker: true,
    randomUUID: true,
    sendBeacon: true,
    simulatedCompat: false,
    ...over,
  });

  it('main, modern browser: every path', () => {
    expect(voiceUnsupportedReason(caps(), null, 'main')).toBeNull();
    expect(voiceUnsupportedReason(caps(), 'webrtc', 'main')).toBeNull();
    expect(voiceUnsupportedReason(caps(), 'websocket', 'main')).toBeNull();
  });

  it('P-8: iOS 12 / ?caps=compat (no AudioWorklet): OpenAI WebRTC yes, Qwen/Gemini relay no', () => {
    const ios12 = caps({ audioWorklet: false, mediaRecorder: false, simulatedCompat: true });
    expect(voiceUnsupportedReason(ios12, 'webrtc', 'compat')).toBeNull();
    expect(voiceUnsupportedReason(ios12, 'websocket', 'compat')).toBe(VOICE_RELAY_UNSUPPORTED);
    expect(voiceUnsupportedReason(ios12, null, 'compat')).toBeNull();
  });

  it('the compat build has no worklet file: the relay is off there even with AudioWorklet', () => {
    expect(voiceUnsupportedReason(caps(), 'websocket', 'compat')).toBe(VOICE_RELAY_UNSUPPORTED);
  });

  it('plain HTTP, no mic API, no WebRTC', () => {
    expect(voiceUnsupportedReason(caps({ secureContext: false }), null, 'main')).toBe(VOICE_NEEDS_HTTPS);
    expect(voiceUnsupportedReason(caps({ getUserMedia: false }), null, 'main')).toBe(VOICE_UNSUPPORTED);
    expect(voiceUnsupportedReason(caps({ webrtc: false }), 'webrtc', 'main')).toBe(VOICE_WEBRTC_UNSUPPORTED);
    expect(voiceUnsupportedReason(caps({ webrtc: false, audioWorklet: false }), null, 'main')).toBe(VOICE_UNSUPPORTED);
    expect(voiceUnsupportedReason(null)).toBe(VOICE_UNSUPPORTED);
  });

  it('provider → transport; connection_info parsing', () => {
    expect(transportForProvider('openai')).toBe('webrtc');
    expect(transportForProvider('qwen')).toBe('websocket');
    expect(transportForProvider('google')).toBe('websocket');
    expect(transportForProvider('local')).toBeNull();
    expect(transportForProvider(undefined)).toBeNull();
    expect(parseConnectionInfo({ connection_type: 'websocket', audio_in_format: { sample_rate: 16000 } })).toEqual({
      connectionType: 'websocket',
      endpoint: null,
      token: null,
      inRate: 16000,
      outRate: 24000,
    });
    expect(parseConnectionInfo({ connection_type: 'sip' })).toBeNull();
    expect(parseConnectionInfo(null)).toBeNull();
  });
});

describe('the pcm-capture worklet file (W-2 conformance)', () => {
  const src = readFileSync(resolve(HERE, '../../../public-main/pcm-capture-worklet.js'), 'utf8');

  function loadProcessor(): new (o: unknown) => { process(i: Float32Array[][]): boolean; port: { posted: unknown[] } } {
    let registered: { name: string; cls: unknown } | null = null;
    class AudioWorkletProcessor {
      port = {
        posted: [] as unknown[],
        postMessage(m: unknown) {
          this.posted.push(m);
        },
      };
    }
    const fn = new Function('AudioWorkletProcessor', 'registerProcessor', 'sampleRate', src);
    fn(AudioWorkletProcessor, (name: string, cls: unknown) => (registered = { name, cls }), 48000);
    const reg = registered as { name: string; cls: unknown } | null;
    expect(reg?.name).toBe(PCM_CAPTURE_PROCESSOR);
    return reg?.cls as never;
  }

  it('registers exactly the processor name the engine instantiates', () => {
    loadProcessor();
    expect(PCM_CAPTURE_PROCESSOR).toBe('pcm-capture');
  });

  it('resamples 48 kHz → 16 kHz and posts PCM16 chunks of 100 ms (1 600 samples)', () => {
    const P = loadProcessor();
    const p = new P({ processorOptions: { targetSampleRate: 16000, chunkMs: 100 } });
    const block = new Float32Array(128).fill(0.5);
    for (let i = 0; i < 40; i++) p.process([[block]]); // 5 120 input samples ≈ 106 ms
    expect(p.port.posted).toHaveLength(1);
    const m = p.port.posted[0] as { type: string; buffer: ArrayBuffer; sampleRate: number };
    expect(m.type).toBe('pcm');
    expect(m.sampleRate).toBe(16000);
    expect(m.buffer.byteLength).toBe(3200);
    expect(new DataView(m.buffer).getInt16(0, true)).toBe(Math.round(0.5 * 0x7fff));
  });

  it('is served under the app base path', () => {
    expect(workletUrl('/next/')).toBe('/next/pcm-capture-worklet.js');
    expect(workletUrl('/')).toBe('/pcm-capture-worklet.js');
  });
});
