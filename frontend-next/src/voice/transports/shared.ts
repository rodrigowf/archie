/** Helpers shared by both transports. */

/** Human text for a `getUserMedia` failure. */
export function micErrorText(err: unknown): string {
  const name = err && typeof err === 'object' && 'name' in err ? String((err as { name: unknown }).name) : '';
  if (name === 'NotAllowedError' || name === 'SecurityError' || name === 'PermissionDeniedError')
    return 'Microphone access was denied. Allow the microphone for this site, then try again.';
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError') return 'No microphone was found.';
  if (name === 'NotReadableError' || name === 'TrackStartError') return 'The microphone is in use by another app.';
  return err instanceof Error && err.message ? err.message : 'Could not open the microphone.';
}

export interface MediaDevicesLike {
  getUserMedia(c: MediaStreamConstraints): Promise<MediaStream>;
}

export function defaultMediaDevices(): MediaDevicesLike | null {
  const nav = typeof navigator !== 'undefined' ? navigator : undefined;
  const md = nav?.mediaDevices;
  return md && typeof md.getUserMedia === 'function' ? md : null;
}

/** Mic constraints of inv02 F-26: echo cancellation + noise suppression, the provider's input rate (ignored where unsupported). */
export function micConstraints(sampleRate: number): MediaStreamConstraints {
  return { audio: { echoCancellation: true, noiseSuppression: true, sampleRate } };
}

export function stopStream(stream: MediaStream | null): void {
  stream?.getTracks().forEach((t) => {
    try {
      t.stop();
    } catch {
      // fine
    }
  });
}

export function setStreamEnabled(stream: MediaStream | null, enabled: boolean): void {
  stream?.getAudioTracks().forEach((t) => {
    t.enabled = enabled;
  });
}
