/**
 * UUID v4 with fallbacks. crypto.randomUUID is missing on Safari 12 AND on any plain-HTTP origin
 * (it needs a secure context), so it cannot be called directly anywhere else (ESLint ban).
 *
 * LOAD-BEARING inv02 F-20 (frontend/src/utils/uuid.ts:7-36): randomUUID → getRandomValues v4 →
 * Math.random.
 */
export interface UuidCrypto {
  randomUUID?: () => string;
  getRandomValues?: (array: Uint8Array) => Uint8Array;
}

function hex(byte: number): string {
  return (byte < 16 ? '0' : '') + byte.toString(16);
}

export function generateUUID(c: UuidCrypto | undefined = typeof crypto !== 'undefined' ? crypto : undefined): string {
  if (c && typeof c.randomUUID === 'function') {
    try {
      return c.randomUUID();
    } catch {
      // Some browsers expose it but throw outside a secure context; fall through.
    }
  }
  if (c && typeof c.getRandomValues === 'function') {
    const bytes = new Uint8Array(16);
    c.getRandomValues(bytes);
    bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40; // version 4
    bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80; // RFC 4122 variant
    let s = '';
    for (let i = 0; i < 16; i++) {
      if (i === 4 || i === 6 || i === 8 || i === 10) s += '-';
      s += hex(bytes[i] ?? 0);
    }
    return s;
  }
  // Last resort, not cryptographically secure.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (ch) => {
    const r = (Math.random() * 16) | 0;
    const v = ch === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
