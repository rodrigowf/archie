/**
 * WAV encoding for the audio-message fallback (spec 13 §2.5: Safari 12 has no MediaRecorder; the
 * PCM comes from a ScriptProcessor capture). Mono PCM16 at 16 kHz, linear resampling, the
 * `format: "wav"` the backend accepts (inv01 §5.2).
 */
export const WAV_RATE = 16_000;

/** Concatenate Float32 chunks and resample linearly from `fromRate` to `toRate`. */
export function resample(chunks: readonly Float32Array[], fromRate: number, toRate: number = WAV_RATE): Float32Array {
  let total = 0;
  for (const c of chunks) total += c.length;
  const input = new Float32Array(total);
  let off = 0;
  for (const c of chunks) {
    input.set(c, off);
    off += c.length;
  }
  if (fromRate === toRate || total === 0) return input;
  const ratio = fromRate / toRate;
  const outLen = Math.floor(total / ratio);
  const out = new Float32Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const pos = i * ratio;
    const i0 = Math.floor(pos);
    const i1 = Math.min(i0 + 1, total - 1);
    const t = pos - i0;
    out[i] = (input[i0] ?? 0) * (1 - t) + (input[i1] ?? 0) * t;
  }
  return out;
}

function writeAscii(view: DataView, offset: number, s: string): void {
  for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i));
}

/** A RIFF/WAVE file: 44-byte header + little-endian PCM16 mono samples. */
export function encodeWav(samples: Float32Array, sampleRate: number = WAV_RATE): ArrayBuffer {
  const buf = new ArrayBuffer(44 + samples.length * 2);
  const v = new DataView(buf);
  writeAscii(v, 0, 'RIFF');
  v.setUint32(4, 36 + samples.length * 2, true);
  writeAscii(v, 8, 'WAVE');
  writeAscii(v, 12, 'fmt ');
  v.setUint32(16, 16, true); // fmt chunk size
  v.setUint16(20, 1, true); // PCM
  v.setUint16(22, 1, true); // mono
  v.setUint32(24, sampleRate, true);
  v.setUint32(28, sampleRate * 2, true); // byte rate
  v.setUint16(32, 2, true); // block align
  v.setUint16(34, 16, true); // bits per sample
  writeAscii(v, 36, 'data');
  v.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const x = Math.max(-1, Math.min(1, samples[i] ?? 0));
    v.setInt16(44 + i * 2, x < 0 ? x * 0x8000 : x * 0x7fff, true);
  }
  return buf;
}
