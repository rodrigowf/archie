/**
 * Base64 ⇄ bytes for PCM16 chunks. `btoa` takes a binary string; it is built in 4096-byte steps
 * (inv02 F-26, `frontend/src/voice/transports/websocket.ts:52-61`) so a 100 ms chunk never hits
 * the argument-count limit of `String.fromCharCode.apply` on old Safari.
 */
const STEP = 4096;

export function bytesToBase64(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i += STEP) {
    const part = bytes.subarray(i, Math.min(i + STEP, bytes.length));
    bin += String.fromCharCode.apply(null, part as unknown as number[]);
  }
  return btoa(bin);
}

export function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** PCM16 little-endian bytes → Float32 samples in [-1, 1). */
export function pcm16ToFloat32(bytes: Uint8Array): Float32Array {
  const n = bytes.length >> 1;
  const out = new Float32Array(n);
  const view = new DataView(bytes.buffer, bytes.byteOffset, n * 2);
  for (let i = 0; i < n; i++) out[i] = view.getInt16(i * 2, true) / 0x8000;
  return out;
}

/** Concatenate byte chunks. */
export function concatBytes(chunks: readonly Uint8Array[]): Uint8Array {
  let len = 0;
  for (const c of chunks) len += c.length;
  const out = new Uint8Array(len);
  let o = 0;
  for (const c of chunks) {
    out.set(c, o);
    o += c.length;
  }
  return out;
}
