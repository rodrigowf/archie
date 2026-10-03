import { afterEach, describe, expect, it } from 'vitest';
import { detectCapabilities, getCapabilities, isCompatSimulated, resetCapabilitiesCache } from './capabilities';

type W = Parameters<typeof detectCapabilities>[0];

function fakeWindow(extra: Record<string, unknown>, search = ''): W {
  return {
    isSecureContext: true,
    location: { search },
    navigator: {
      mediaDevices: { getUserMedia: () => undefined },
      clipboard: { writeText: () => Promise.resolve() },
      sendBeacon: () => true,
      maxTouchPoints: 0,
      serviceWorker: {},
    },
    crypto: { randomUUID: () => 'x' },
    AudioContext: function AudioContext() {},
    AudioWorkletNode: function AudioWorkletNode() {},
    MediaRecorder: function MediaRecorder() {},
    RTCPeerConnection: function RTCPeerConnection() {},
    ResizeObserver: function ResizeObserver() {},
    PointerEvent: function PointerEvent() {},
    ...extra,
  } as unknown as W;
}

afterEach(() => {
  resetCapabilitiesCache();
});

describe('detectCapabilities', () => {
  it('detects a modern secure browser', () => {
    const caps = detectCapabilities(fakeWindow({}));
    expect(caps).toMatchObject({
      secureContext: true,
      audioWorklet: true,
      mediaRecorder: true,
      clipboardApi: true,
      webrtc: true,
      getUserMedia: true,
      randomUUID: true,
      simulatedCompat: false,
    });
  });

  it('models iOS 12 Safari: webkitAudioContext only, no worklet/recorder/clipboard/RO', () => {
    const caps = detectCapabilities(
      fakeWindow({
        AudioContext: undefined,
        webkitAudioContext: function webkitAudioContext() {},
        AudioWorkletNode: undefined,
        MediaRecorder: undefined,
        ResizeObserver: undefined,
        PointerEvent: undefined,
        crypto: {},
        navigator: { mediaDevices: { getUserMedia: () => undefined }, maxTouchPoints: 5 },
      }),
    );
    expect(caps).toMatchObject({
      webAudio: true,
      audioWorklet: false,
      mediaRecorder: false,
      clipboardApi: false,
      resizeObserver: false,
      pointerEvents: false,
      randomUUID: false,
      touch: true,
    });
  });

  it('plain-HTTP origin: no worklet and no clipboard API even when the objects exist', () => {
    const caps = detectCapabilities(fakeWindow({ isSecureContext: false }));
    expect(caps.audioWorklet).toBe(false);
    expect(caps.clipboardApi).toBe(false);
  });

  it('?caps=compat forces the compat capability set', () => {
    expect(isCompatSimulated('?caps=compat')).toBe(true);
    expect(isCompatSimulated('?a=1&caps=compat&b=2')).toBe(true);
    expect(isCompatSimulated('?caps=compatx')).toBe(false);
    const caps = detectCapabilities(fakeWindow({}, '?caps=compat'));
    expect(caps).toMatchObject({ simulatedCompat: true, audioWorklet: false, mediaRecorder: false, clipboardApi: false });
    expect(caps.webrtc).toBe(true);
  });

  it('getCapabilities memoizes the current window', () => {
    expect(getCapabilities()).toBe(getCapabilities());
  });
});
