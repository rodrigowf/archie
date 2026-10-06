/**
 * One `VoiceController` per live Archie conversation, attached to its runtime through the
 * `VoiceHooks` bridge (W-06) the moment the runtime is registered, before its socket can deliver
 * a frame. Controllers follow the session registry: a closed Archie tab disposes its controller
 * (local media released, nothing sent, P-1).
 */
import { getCapabilities, isDebugEnabled } from '@/platform';
import type { SessionStartedFrame } from '@/protocol';
import { api, ArchieRuntime, getSessionRuntime, listRuntimes } from '@/services';
import { serverConfigStore, sessionRegistryVersion } from '@/stores';
import { workletNodeCtor, workletUrl } from './audio/capture/worklet';
import { sharedAudioContext, sharedAudioElement, unlockAudio } from './audio/context';
import { WebAudioCuePlayer } from './audio/cues';
import { transportForProvider, voiceUnsupportedReason } from './core/support';
import type { ConnectionType, NetworkSource, TransportFactory, VoicePort } from './core/types';
import { VoiceController } from './core/VoiceController';
import { defaultMediaDevices } from './transports/shared';
import { WebRtcTransport, type PeerConnectionLike, type WebRtcEnv } from './transports/webrtc';
import { WsRelayTransport } from './transports/wsRelay';

const controllers = new Map<ArchieRuntime, VoiceController>();
let installed = false;
let unsubscribeRegistry: (() => void) | null = null;

function vlog(...args: unknown[]): void {
  if (isDebugEnabled('voice')) console.info('[voice]', ...args);
}

/** VoicePort over ArchieRuntime's VoiceBridge. */
export function portFor(rt: ArchieRuntime): VoicePort {
  return {
    get localId() {
      return rt.localId;
    },
    sdkId: () => rt.conv.ref.sdkId,
    requestStart: () => {
      rt.retry();
    },
    send: (msg) => rt.sendVoice(msg),
    feedDataChannelEvent: (e) => {
      rt.feedDataChannelEvent(e);
    },
    voiceLocalEnd: () => {
      rt.voiceLocalEnd();
    },
    conversationVoiceActive: () => rt.conv.voiceActive,
  };
}

const browserNetwork: NetworkSource = {
  isOnline: () => (typeof navigator === 'undefined' ? true : navigator.onLine !== false),
  subscribe(fn) {
    if (typeof window === 'undefined') return () => undefined;
    const on = (): void => fn(true);
    const off = (): void => fn(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  },
};

/** The production transport factory (browser APIs). */
export const browserTransports: TransportFactory = (kind, opts) => {
  const media = defaultMediaDevices() ?? { getUserMedia: () => Promise.reject(new Error('No microphone API')) };
  const WorkletNode = workletNodeCtor();
  if (kind === 'webrtc') {
    const win = window as unknown as { RTCPeerConnection: new () => PeerConnectionLike };
    const env: WebRtcEnv = {
      RTCPeerConnection: win.RTCPeerConnection,
      media,
      fetch: (url, init) => fetch(url, init),
      audioElement: sharedAudioElement,
      audioContext: sharedAudioContext,
      WorkletNode: getCapabilities().audioWorklet ? WorkletNode : null,
      workletUrl: workletUrl(),
    };
    return new WebRtcTransport(opts.info, opts.events, env, opts.record);
  }
  return new WsRelayTransport(opts.info, opts.events, {
    media,
    audioContext: sharedAudioContext,
    WorkletNode,
    workletUrl: workletUrl(),
  });
};

/** V-3 fallback token: all five parameters, from `session_started` + the server config. */
function fetchConnectionInfo(f: SessionStartedFrame): Promise<unknown> {
  const cfg = serverConfigStore.getState().config;
  return api.voice
    .session({
      provider: f.voice_provider ?? cfg?.default_voice_provider ?? '',
      model: f.voice_model ?? cfg?.default_voice_model ?? '',
      voice: f.voice_name ?? cfg?.default_voice_name ?? '',
      transcription_language: f.voice_transcription_language ?? cfg?.default_voice_transcription_language ?? '',
      endpoint: cfg?.default_voice_endpoint ?? '',
    })
    .then((r) => r.connection_info ?? null);
}

const cues = new WebAudioCuePlayer(sharedAudioContext);

function createController(rt: ArchieRuntime): VoiceController {
  const c = new VoiceController({
    port: portFor(rt),
    createTransport: browserTransports,
    cues,
    network: browserNetwork,
    unsupported: (kind: ConnectionType) => voiceUnsupportedReason(getCapabilities(), kind),
    fetchConnectionInfo,
    log: vlog,
  });
  rt.setVoiceHooks({
    startMessage: () => c.startMessage(),
    onFrame: (f) => {
      vlog('frame', f.type);
      c.onFrame(f);
    },
    onSocketClosed: () => {
      c.onSocketClosed();
    },
  });
  return c;
}

/** Attach controllers to new Archie runtimes; dispose the ones whose runtime left. */
export function syncVoiceControllers(): void {
  controllers.forEach((c, rt) => {
    if (rt.isDisposed || getSessionRuntime(rt.localId) !== rt) {
      c.dispose();
      controllers.delete(rt);
    }
  });
  for (const rt of listRuntimes()) {
    if (rt instanceof ArchieRuntime && !rt.readOnly && !rt.isDisposed && !controllers.has(rt)) controllers.set(rt, createController(rt));
  }
}

/** Start following the session registry (idempotent). */
export function installVoiceEngine(): void {
  if (installed) return;
  installed = true;
  syncVoiceControllers();
  unsubscribeRegistry = sessionRegistryVersion.subscribe(() => {
    syncVoiceControllers();
  });
}

/** Tests / teardown. */
export function uninstallVoiceEngine(): void {
  unsubscribeRegistry?.();
  unsubscribeRegistry = null;
  installed = false;
  controllers.forEach((c) => c.dispose());
  controllers.clear();
}

/** `@/voice` entry (spec 13 §3.9): the controller of a live Archie conversation. */
export function getVoiceController(localId: string): VoiceController | undefined {
  const rt = getSessionRuntime(localId);
  if (!(rt instanceof ArchieRuntime) || rt.readOnly) return undefined;
  let c = controllers.get(rt);
  if (!c && !rt.isDisposed) {
    c = createController(rt);
    controllers.set(rt, c);
  }
  return c;
}

/**
 * The composer's Voice action (W-11 `setStartVoiceHandler`). Call from the tap: unlocks audio
 * synchronously (iOS), then starts. Returns the reason when voice cannot run here.
 */
export function startVoiceFromGesture(localId: string): string | null {
  const c = getVoiceController(localId);
  if (!c) return 'Open Archie to start voice';
  const cfg = serverConfigStore.getState().config;
  const reason = voiceUnsupportedReason(getCapabilities(), transportForProvider(cfg?.default_voice_provider));
  if (reason) return reason;
  unlockAudio();
  if (c.snapshot.status === 'error') c.dismissError();
  c.start();
  return null;
}
