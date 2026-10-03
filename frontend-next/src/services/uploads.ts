/**
 * `POST /api/uploads` with progress (spec 13 §3.4, spec 12 §6.15). XHR rather than fetch because
 * Safari 12 has no upload progress on fetch. The browser streams the `File` from disk; nothing
 * reads it into memory (A-3.4).
 *
 * G-1: until BF-3 (`client_max_body_size 200m`) is deployed on the Jetson, nginx rejects bodies
 * over 1 MiB with an **HTML** 413 before the app sees them → `PayloadTooLargeError` with
 * "File too large for the server (nginx limit 1 MB)". The app's own JSON 413 ("File exceeds the
 * 200 MB upload limit.") keeps its verbatim detail.
 */
import { markBackendOffline, markBackendOnline } from '@/stores';
import { getEnv, httpUrl } from './env';
import { AbortedError, NetworkError, TimeoutError, errorFromResponse } from './http/errors';
import type { UploadResult } from './http/types';

export interface UploadProgress {
  loaded: number;
  total: number;
  /** 0–1, or null when the total is unknown. */
  fraction: number | null;
}

export interface UploadOptions {
  onProgress?: (p: UploadProgress) => void;
  signal?: AbortSignal;
  /** Default 10 min. */
  timeoutMs?: number;
}

export const UPLOAD_TIMEOUT_MS = 10 * 60_000;

export function uploadFile(file: Blob & { name?: string }, opts: UploadOptions = {}): Promise<UploadResult> {
  const env = getEnv();
  const Xhr = env.XMLHttpRequest;
  if (!Xhr) return Promise.reject(new NetworkError('Uploads are not supported in this browser'));
  return new Promise<UploadResult>((resolve, reject) => {
    const xhr = new Xhr();
    xhr.open('POST', httpUrl('/api/uploads'));
    xhr.timeout = opts.timeoutMs ?? UPLOAD_TIMEOUT_MS;
    xhr.setRequestHeader('accept', 'application/json');
    let settled = false;
    const done = (fn: () => void): void => {
      if (settled) return;
      settled = true;
      opts.signal?.removeEventListener('abort', onAbort);
      fn();
    };
    const onAbort = (): void => {
      xhr.abort();
      done(() => reject(new AbortedError()));
    };
    if (opts.signal) {
      if (opts.signal.aborted) {
        reject(new AbortedError());
        return;
      }
      opts.signal.addEventListener('abort', onAbort);
    }
    if (xhr.upload && opts.onProgress) {
      const cb = opts.onProgress;
      xhr.upload.onprogress = (ev: ProgressEvent) => {
        cb({ loaded: ev.loaded, total: ev.total, fraction: ev.lengthComputable && ev.total > 0 ? ev.loaded / ev.total : null });
      };
    }
    xhr.onload = () =>
      done(() => {
        markBackendOnline();
        const ct = xhr.getResponseHeader('content-type') ?? '';
        const text = typeof xhr.responseText === 'string' ? xhr.responseText : '';
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            resolve(JSON.parse(text) as UploadResult);
          } catch {
            reject(errorFromResponse(500, ct, 'The server sent an unreadable answer'));
          }
          return;
        }
        reject(errorFromResponse(xhr.status, ct, text));
      });
    xhr.onerror = () =>
      done(() => {
        // A browser reports an nginx 413 without CORS headers as a network error; status 0.
        const e = new NetworkError("Can't reach the server (the file may be too large for the server)");
        markBackendOffline(e.message);
        reject(e);
      });
    xhr.ontimeout = () => done(() => reject(new TimeoutError('The upload took too long')));
    const form = new FormData();
    form.append('file', file, file.name ?? 'upload');
    xhr.send(form);
  });
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * The orchestrator line for an uploaded file (spec 12 §6.15; the Android format,
 * `AssistantViewModel.kt:262-332`).
 */
export function sharedFileText(r: UploadResult, subject?: string): string {
  let t = `[shared file] ${r.filename} (${formatSize(r.size)}, ${r.content_type}) — ${r.url}`;
  if (subject && subject.trim()) t += `\nNote: ${subject.trim()}`;
  return `${t}\nLocal path: ${r.path}`;
}

/** The orchestrator line for shared text (spec 12 §6.15). */
export function sharedTextText(text: string, subject?: string): string {
  return `[shared text]${subject && subject.trim() ? ` ${subject.trim()}` : ''}\n${text.trim()}`;
}
