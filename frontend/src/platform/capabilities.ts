/**
 * Client capability detection (spec 13 §2.5). Features are chosen at runtime, not by build flag,
 * so a modern Safari loading /compat/ still gets the better path.
 *
 * `?caps=compat` simulates the compat capability set in Chrome for screenshots (spec 13 §6.4):
 * no AudioWorklet, no MediaRecorder, no Clipboard API.
 */
export interface ClientCapabilities {
  secureContext: boolean;
  webAudio: boolean;
  audioWorklet: boolean;
  mediaRecorder: boolean;
  getUserMedia: boolean;
  webrtc: boolean;
  clipboardApi: boolean;
  /** ResizeObserver available (on compat it is the @juggle polyfill, installed before detection). */
  resizeObserver: boolean;
  pointerEvents: boolean;
  touch: boolean;
  serviceWorker: boolean;
  randomUUID: boolean;
  sendBeacon: boolean;
  /** true when `?caps=compat` forced the compat set. */
  simulatedCompat: boolean;
}

type CapWindow = Window & typeof globalThis & { webkitAudioContext?: unknown };

export function isCompatSimulated(search: string): boolean {
  return /(?:^|[?&])caps=compat(?:&|$)/.test(search);
}

export function detectCapabilities(win: CapWindow = window as CapWindow): ClientCapabilities {
  const nav = win.navigator;
  const secureContext = win.isSecureContext === true;
  const simulatedCompat = isCompatSimulated(win.location?.search ?? '');
  const caps: ClientCapabilities = {
    secureContext,
    webAudio: typeof win.AudioContext === 'function' || typeof win.webkitAudioContext === 'function',
    // AudioWorklet needs a secure context (and Safari 14.1+).
    audioWorklet: secureContext && typeof (win as { AudioWorkletNode?: unknown }).AudioWorkletNode === 'function',
    mediaRecorder: typeof (win as { MediaRecorder?: unknown }).MediaRecorder === 'function',
    getUserMedia: !!nav.mediaDevices && typeof nav.mediaDevices.getUserMedia === 'function',
    webrtc: typeof (win as { RTCPeerConnection?: unknown }).RTCPeerConnection === 'function',
    clipboardApi: secureContext && !!nav.clipboard && typeof nav.clipboard.writeText === 'function',
    resizeObserver: typeof (win as { ResizeObserver?: unknown }).ResizeObserver === 'function',
    pointerEvents: typeof (win as { PointerEvent?: unknown }).PointerEvent === 'function',
    touch: 'ontouchstart' in win || (nav.maxTouchPoints ?? 0) > 0,
    serviceWorker: 'serviceWorker' in nav,
    randomUUID: typeof win.crypto !== 'undefined' && typeof win.crypto.randomUUID === 'function',
    sendBeacon: typeof nav.sendBeacon === 'function',
    simulatedCompat,
  };
  if (simulatedCompat) {
    caps.audioWorklet = false;
    caps.mediaRecorder = false;
    caps.clipboardApi = false;
  }
  return caps;
}

let cached: ClientCapabilities | null = null;

/** Memoized detection for the current window. */
export function getCapabilities(): ClientCapabilities {
  if (!cached) cached = detectCapabilities();
  return cached;
}

export function resetCapabilitiesCache(): void {
  cached = null;
}
