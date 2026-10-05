/**
 * Voice capability gating (spec 13 §2.5; plan/20 P-8). Pure: the caller passes the detected
 * client capabilities. The UI never offers a voice path that cannot work; when it cannot, it
 * says why.
 *
 * - OpenAI realtime runs over WebRTC (works on iOS 12, best effort).
 * - Qwen / Gemini need PCM capture through an AudioWorklet (Safari 14.1+). P-8: on iOS 12 they
 *   are unavailable (no ScriptProcessor fallback). The compat build also does not ship the
 *   worklet file (it lives in `public-main/`), so the WS relay is off on `/compat/`.
 */
import type { ClientCapabilities } from '@/platform';
import type { ConnectionInfo, ConnectionType } from './types';

export const VOICE_NEEDS_HTTPS = 'Voice needs HTTPS';
export const VOICE_UNSUPPORTED = 'Voice is not supported on this browser';
export const VOICE_RELAY_UNSUPPORTED =
  'Qwen and Gemini voice need a newer browser. Switch Archie’s voice to OpenAI to talk from this device.';
export const VOICE_WEBRTC_UNSUPPORTED = 'OpenAI voice needs WebRTC, which this browser does not have.';

/** Which transport a provider id uses (inv01 §7: OpenAI WebRTC; Qwen and Google relayed by the backend). */
export function transportForProvider(provider: string | null | undefined): ConnectionType | null {
  if (!provider) return null;
  const p = provider.toLowerCase();
  if (p === 'openai') return 'webrtc';
  if (p === 'qwen' || p === 'google' || p === 'gemini') return 'websocket';
  return null;
}

function relayPossible(caps: ClientCapabilities, target: 'main' | 'compat'): boolean {
  return target === 'main' && caps.audioWorklet && caps.webAudio;
}

/**
 * `null` when voice can run, else the human reason. Without `kind` the question is "can any
 * voice path run here?"; with it, "can this transport run here?".
 */
export function voiceUnsupportedReason(
  caps: ClientCapabilities | null,
  kind: ConnectionType | null = null,
  target: 'main' | 'compat' = __TARGET__,
): string | null {
  if (!caps) return VOICE_UNSUPPORTED;
  if (!caps.secureContext) return VOICE_NEEDS_HTTPS;
  if (!caps.getUserMedia) return VOICE_UNSUPPORTED;
  if (kind === 'webrtc') return caps.webrtc ? null : VOICE_WEBRTC_UNSUPPORTED;
  if (kind === 'websocket') return relayPossible(caps, target) ? null : VOICE_RELAY_UNSUPPORTED;
  return caps.webrtc || relayPossible(caps, target) ? null : VOICE_UNSUPPORTED;
}

function num(v: unknown, fallback: number): number {
  return typeof v === 'number' && isFinite(v) && v > 0 ? v : fallback;
}

/** `voice_connection_info` → typed info. Null when the shape is unusable. */
export function parseConnectionInfo(raw: unknown): ConnectionInfo | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  const t = r.connection_type;
  if (t !== 'webrtc' && t !== 'websocket') return null;
  const inFmt = (r.audio_in_format ?? {}) as Record<string, unknown>;
  const outFmt = (r.audio_out_format ?? {}) as Record<string, unknown>;
  const fallback = t === 'webrtc' ? 24000 : 16000;
  return {
    connectionType: t,
    endpoint: typeof r.endpoint === 'string' ? r.endpoint : null,
    token: typeof r.ephemeral_token === 'string' ? r.ephemeral_token : null,
    inRate: num(inFmt.sample_rate, fallback),
    outRate: num(outFmt.sample_rate, 24000),
  };
}
