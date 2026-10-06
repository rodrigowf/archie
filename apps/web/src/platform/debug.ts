/**
 * Debug switches and the `window.__archie` handle (spec 13 §3.1; inv02 F-39).
 * `?debug=voice` enables one channel, `?debug=all` (or `?debug=1`) enables every channel;
 * several channels can be comma-separated (`?debug=voice,ws`).
 */
export interface ArchieDebugHandle {
  target: 'main' | 'compat';
  version: string;
  buildTime: string;
  [hook: string]: unknown;
}

declare global {
  interface Window {
    __archie?: ArchieDebugHandle;
  }
}

export function parseDebugChannels(search: string): Set<string> {
  const out = new Set<string>();
  const match = /(?:^|[?&])debug=([^&]*)/.exec(search);
  if (!match) return out;
  for (const raw of decodeURIComponent(match[1] ?? '').split(',')) {
    const ch = raw.trim().toLowerCase();
    if (ch) out.add(ch === '1' || ch === 'true' ? 'all' : ch);
  }
  return out;
}

let channels: Set<string> | null = null;

export function isDebugEnabled(channel: string): boolean {
  if (!channels) channels = parseDebugChannels(typeof location !== 'undefined' ? location.search : '');
  return channels.has('all') || channels.has(channel.toLowerCase());
}

export function resetDebugChannels(search?: string): void {
  channels = search === undefined ? null : parseDebugChannels(search);
}

/** Installs window.__archie (idempotent) and returns it. */
export function installDebugHandle(): ArchieDebugHandle {
  if (!window.__archie) {
    window.__archie = { target: __TARGET__, version: __APP_VERSION__, buildTime: __BUILD_TIME__ };
  }
  return window.__archie;
}

/** Exposes a value for devtools / automation, e.g. exposeDebug('setShowConfig', fn). */
export function exposeDebug(name: string, value: unknown): void {
  installDebugHandle()[name] = value;
}
