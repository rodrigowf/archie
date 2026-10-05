/**
 * Uploads (§6.15): XHR multipart with progress; nginx's HTML 413 (G-1, until BF-3 is deployed)
 * → "File too large for the server (nginx limit 1 MB)"; the app's own JSON 413 keeps its detail;
 * the shared-file line format; share → inject_text on the orchestrator socket.
 * Real round trips run against the mock backend on a random port (`nginx413` option).
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startMockServer, type MockServer } from '../../../mock-server/server.mjs';
import {
  AbortedError,
  configureServices,
  NetworkError,
  openSession,
  PAYLOAD_TOO_LARGE_MESSAGE,
  PayloadTooLargeError,
  shareFile,
  sharedFileText,
  sharedTextText,
  shareText,
  startServices,
  TimeoutError,
  uploadFile,
  type ArchieRuntime,
} from '@/services';
import { FakeWebSocket, setupServices, teardownServices } from './fakes';

let plain: MockServer;
let nginx: MockServer;

beforeAll(async () => {
  plain = await startMockServer({ port: 0, host: '127.0.0.1', fast: true, quiet: true });
  nginx = await startMockServer({ port: 0, host: '127.0.0.1', fast: true, quiet: true, nginx413: true });
});
afterAll(async () => {
  await plain.close();
  await nginx.close();
});
beforeEach(() => {
  setupServices();
  configureServices({ XMLHttpRequest: window.XMLHttpRequest });
});
afterEach(() => teardownServices());

const blob = (bytes: number, name = 'notes.txt') => Object.assign(new Blob([new Uint8Array(bytes).fill(65)], { type: 'text/plain' }), { name });

describe('against the mock backend', () => {
  it('uploads a small file with progress and returns the server record', async () => {
    configureServices({ baseUrl: plain.url });
    const seen: number[] = [];
    const r = await uploadFile(blob(2048), { onProgress: (p) => seen.push(p.loaded) });
    expect(r).toMatchObject({ filename: 'notes.txt', url: expect.stringMatching(/^\/uploads\/.+-notes\.txt$/) as unknown });
    expect(r.size).toBeGreaterThan(2048); // the mock counts the multipart body
  });

  it('a body over 1 MiB through nginx gives the clear 413 message (G-1, before BF-3)', async () => {
    configureServices({ baseUrl: nginx.url });
    const err = await uploadFile(blob(1024 * 1024 + 10, 'big.bin')).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(PayloadTooLargeError);
    expect((err as Error).message).toBe(PAYLOAD_TOO_LARGE_MESSAGE);
  });
});

/** A scripted XMLHttpRequest. */
class FakeXhr {
  static next: (x: FakeXhr) => void = () => undefined;
  status = 0;
  responseText = '';
  timeout = 0;
  headers: Record<string, string> = {};
  responseHeaders: Record<string, string> = {};
  upload: { onprogress: ((ev: ProgressEvent) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  ontimeout: (() => void) | null = null;
  aborted = false;
  body: unknown;
  open(): void {}
  setRequestHeader(k: string, v: string): void {
    this.headers[k] = v;
  }
  getResponseHeader(k: string): string | null {
    return this.responseHeaders[k] ?? null;
  }
  send(body: unknown): void {
    this.body = body;
    FakeXhr.next(this);
  }
  abort(): void {
    this.aborted = true;
  }
  respond(status: number, text: string, contentType: string): void {
    this.status = status;
    this.responseText = text;
    this.responseHeaders['content-type'] = contentType;
    this.onload?.();
  }
}

describe('with a scripted XHR', () => {
  beforeEach(() => configureServices({ XMLHttpRequest: FakeXhr as unknown as typeof XMLHttpRequest }));

  it('maps the app JSON 413 verbatim and reports progress fractions', async () => {
    const progress: (number | null)[] = [];
    FakeXhr.next = (x) => {
      x.upload.onprogress?.({ loaded: 5, total: 10, lengthComputable: true } as ProgressEvent);
      x.upload.onprogress?.({ loaded: 5, total: 0, lengthComputable: false } as ProgressEvent);
      x.respond(413, JSON.stringify({ detail: 'File exceeds the 200 MB upload limit.' }), 'application/json');
    };
    const err = await uploadFile(blob(3), { onProgress: (p) => progress.push(p.fraction) }).catch((e: unknown) => e);
    expect((err as Error).message).toBe('File exceeds the 200 MB upload limit.');
    expect(err).not.toBeInstanceOf(PayloadTooLargeError);
    expect(progress).toEqual([0.5, null]);
  });

  it('network error, timeout, abort, unreadable 200', async () => {
    FakeXhr.next = (x) => x.onerror?.();
    await expect(uploadFile(blob(1))).rejects.toBeInstanceOf(NetworkError);
    FakeXhr.next = (x) => x.ontimeout?.();
    await expect(uploadFile(blob(1))).rejects.toBeInstanceOf(TimeoutError);
    const ctl = new AbortController();
    FakeXhr.next = () => ctl.abort();
    await expect(uploadFile(blob(1), { signal: ctl.signal })).rejects.toBeInstanceOf(AbortedError);
    await expect(uploadFile(blob(1), { signal: ctl.signal })).rejects.toBeInstanceOf(AbortedError);
    FakeXhr.next = (x) => x.respond(200, 'not json', 'text/plain');
    await expect(uploadFile(blob(1))).rejects.toThrow('unreadable');
  });

  it('share: upload → the Android-format line → inject_text on the orchestrator socket', async () => {
    FakeXhr.next = (x) =>
      x.respond(200, JSON.stringify({ filename: 'a.pdf', path: '/srv/uploads/a.pdf', url: '/uploads/a.pdf', size: 1536, content_type: 'application/pdf' }), 'application/json');
    await expect(shareFile(blob(1))).rejects.toThrow('Open Archie');
    startServices({ skipInitialSync: true });
    const archie = openSession({ kind: 'archie', localId: 'O1', focus: true }) as ArchieRuntime;
    const ws = FakeWebSocket.last('/api/orchestrator/chat');
    ws.open();
    ws.emit({ type: 'session_started', session_id: 'O1' });
    await shareFile(blob(1), 'for the report');
    shareText('  some text  ', 'clip');
    expect(ws.messages().slice(1)).toEqual([
      { type: 'inject_text', text: '[shared file] a.pdf (1.5 KB, application/pdf) — /uploads/a.pdf\nNote: for the report\nLocal path: /srv/uploads/a.pdf' },
      { type: 'inject_text', text: '[shared text] clip\nsome text' },
    ]);
    expect(archie.conv.entries.map((e) => e.kind === 'user' && e.origin)).toEqual(['inject', 'inject']);
  });

  it('no XHR → a clear error', async () => {
    configureServices({ XMLHttpRequest: null });
    await expect(uploadFile(blob(1))).rejects.toThrow('not supported');
  });
});

describe('formats', () => {
  it('sizes and optional subject', () => {
    const r = { filename: 'f', path: '/p', url: '/u', size: 10, content_type: 't' };
    expect(sharedFileText(r)).toBe('[shared file] f (10 B, t) — /u\nLocal path: /p');
    expect(sharedFileText({ ...r, size: 3 * 1024 * 1024 })).toContain('(3.0 MB, t)');
    expect(sharedTextText('x')).toBe('[shared text]\nx');
  });
});
