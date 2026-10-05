/**
 * REST client: backend `detail` verbatim (fixes "400 Bad Request" swallowing, inv02 §6.2,
 * CFG-2), FastAPI 422 lists, nginx HTML 413 → PayloadTooLargeError (G-1), SPA-fallback probes
 * (G-38), network / timeout errors, reachability, encoding. Plus the catalog/config services.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { catalogStore, connectionStore, serverConfigStore, snackbarStore } from '@/stores';
import {
  ApiError,
  api,
  buildQuery,
  castVisualization,
  duplicateSession,
  encodePath,
  errorFromResponse,
  errorMessage,
  loadConfigCatalogs,
  loadGoogleVoiceModels,
  loadServerConfig,
  NetworkError,
  PAYLOAD_TOO_LARGE_MESSAGE,
  PayloadTooLargeError,
  probeAudioModels,
  probeCast,
  refreshMemoryTree,
  refreshSessionList,
  refreshVisuals,
  renameSession,
  renameVisualization,
  request,
  saveServerConfig,
  TimeoutError,
  tolerate404,
} from '@/services';
import { capabilitiesStore } from '@/stores';
import { jsonResponse, setupServices, teardownServices, textResponse, type Harness } from './fakes';

let h: Harness;
beforeEach(() => {
  h = setupServices();
});
afterEach(() => {
  teardownServices();
  vi.useRealTimers();
});

const NGINX_413 =
  '<html>\r\n<head><title>413 Request Entity Too Large</title></head>\r\n<body>\r\n<center><h1>413 Request Entity Too Large</h1></center>\r\n<hr><center>nginx/1.18.0</center>\r\n</body>\r\n</html>\r\n';

describe('errors', () => {
  it('keeps the backend detail verbatim', async () => {
    h.fetch.on('PUT', '/api/config', () => jsonResponse({ detail: 'Directory does not exist: /home/x' }, 400));
    const err = await api.config.put({ working_directory: 'x' }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(400);
    expect((err as ApiError).detail).toBe('Directory does not exist: /home/x');
    expect(errorMessage(err)).toBe('Directory does not exist: /home/x');
    expect((err as ApiError).raw).toEqual({ detail: 'Directory does not exist: /home/x' });
  });

  it('joins FastAPI 422 validation messages', () => {
    const e = errorFromResponse(422, 'application/json', JSON.stringify({ detail: [{ loc: ['body', 'title'], msg: 'field required' }, { msg: 'bad' }] }));
    expect(e.detail).toBe('title: field required; bad');
  });

  it('maps the nginx HTML 413 to PayloadTooLargeError, but keeps the app JSON 413 detail', () => {
    const html = errorFromResponse(413, 'text/html', NGINX_413);
    expect(html).toBeInstanceOf(PayloadTooLargeError);
    expect(html.detail).toBe(PAYLOAD_TOO_LARGE_MESSAGE);
    const app = errorFromResponse(413, 'application/json', JSON.stringify({ detail: 'File exceeds the 200 MB upload limit.' }));
    expect(app).not.toBeInstanceOf(PayloadTooLargeError);
    expect(app.detail).toBe('File exceeds the 200 MB upload limit.');
  });

  it('falls back to the status text for HTML / empty bodies and to a short text body otherwise', () => {
    expect(errorFromResponse(502, 'text/html', '<html>bad gateway</html>').detail).toBe('502 Bad Gateway');
    expect(errorFromResponse(500, '', '').detail).toBe('500 Internal Server Error');
    expect(errorFromResponse(500, 'text/plain', 'Internal Server Error\n').detail).toBe('Internal Server Error');
    expect(errorFromResponse(418, '', '').detail).toBe('418 Error');
    expect(errorMessage('x')).toBe('x');
    expect(errorMessage(null)).toBe('Something went wrong');
  });

  it('network failures are NetworkError and mark the backend offline; any answer marks it online', async () => {
    h.fetch.fetch.mockImplementationOnce(() => Promise.reject(new TypeError('Failed to fetch')));
    await expect(api.sessions.list()).rejects.toBeInstanceOf(NetworkError);
    expect(connectionStore.getState().backend).toBe('offline');
    await api.sessions.list();
    expect(connectionStore.getState().backend).toBe('online');
  });

  it('times out with TimeoutError', async () => {
    vi.useFakeTimers();
    h.fetch.fetch.mockImplementationOnce(() => new Promise<Response>(() => undefined));
    const p = request('GET', '/api/slow', { timeoutMs: 1000 }).catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(1000);
    expect(await p).toBeInstanceOf(TimeoutError);
  });

  it('a probe that gets the SPA shell (text/html 200) is a 404 (G-38); tolerate404 swallows it', async () => {
    h.fetch.on('GET', '/api/visualizations/cast', () => textResponse('<!doctype html><div id=root>', 200, 'text/html'));
    const err = await api.visuals.castProbe().catch((e: unknown) => e);
    expect(err).toMatchObject({ status: 404 });
    expect(await tolerate404<unknown>(() => api.visuals.castProbe(), 'fallback')).toBe('fallback');
    h.fetch.on('GET', '/api/x', () => jsonResponse({ detail: 'nope' }, 500));
    await expect(tolerate404(() => request('GET', '/api/x'), null)).rejects.toThrow('nope');
  });

  it('204 → undefined, text, unreadable JSON', async () => {
    h.fetch.on('PATCH', '/api/sessions/s1/rename', () => new Response(null, { status: 204 }));
    await expect(api.sessions.rename('s1', 'T')).resolves.toBeUndefined();
    h.fetch.on('GET', '/memory/notes/a%20b.md', () => textResponse('# A', 200, 'text/markdown'));
    await expect(api.memory.file('notes/a b.md')).resolves.toBe('# A');
    h.fetch.on('GET', '/memory/', () => textResponse('# Root', 200, 'text/markdown'));
    await expect(api.memory.file('')).resolves.toBe('# Root');
    h.fetch.on('GET', '/api/weird', () => textResponse('{nope', 200, 'application/json'));
    await expect(request('GET', '/api/weird')).rejects.toThrow('unreadable');
  });

  it('builds queries and encodes path segments (MEM-3, VZ-1)', async () => {
    expect(buildQuery({ a: 1, b: undefined, c: null, d: 'x y' })).toBe('?a=1&d=x%20y');
    expect(buildQuery()).toBe('');
    expect(encodePath('a b/c#d/e?.md')).toBe('a%20b/c%23d/e%3F.md');
    expect(api.visuals.frameUrl('/my viz/index.html')).toBe('/my%20viz/index.html');
    expect(api.visuals.frameUrl('x.html')).toBe('/x.html');
    h.fetch.on('GET', '/api/sessions/s%2F1/messages', { messages: [], total_count: 0, has_more: false, start_index: 0 });
    await api.sessions.messages('s/1', { before: 10 });
    expect(h.fetch.requests.at(-1)?.path).toBe('/api/sessions/s%2F1/messages?limit=50&before=10');
  });

  it('every endpoint hits the documented path and method', async () => {
    const ok = () => jsonResponse({});
    for (const m of ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']) h.fetch.on(m, /.*/, ok);
    await api.sessions.getConfig('s');
    await api.sessions.putConfig('s', { provider: null });
    await api.sessions.duplicate('s');
    await api.sessions.fork('s', 2);
    await api.sessions.truncate('s', 1);
    await api.sessions.remove('s');
    await api.sessions.close('L');
    await api.config.providers();
    await api.config.qwenModels();
    await api.config.googleVoiceModels('vertex');
    await api.catalogs.skills();
    await api.catalogs.agents();
    await api.catalogs.mcpServers();
    await api.catalogs.mcpServer('chrome devtools');
    await api.voice.orchestratorModels();
    await api.voice.audioModels();
    await api.voice.voiceModels();
    await api.voice.session({ provider: 'openai', model: 'm', voice: 'v', transcription_language: '', endpoint: '' });
    await api.memory.tree();
    await api.visuals.list();
    await api.visuals.rename('a.html', 'A');
    await api.visuals.cast('a.html');
    await api.auth.status();
    await api.auth.login();
    await api.auth.credentials('{}');
    h.fetch.on('GET', '/api/debug/log', () => textResponse('No logs yet.\n'));
    await expect(api.debug.readLog()).resolves.toBe('No logs yet.\n');
    expect(h.fetch.requests.map((r) => `${r.method} ${r.path}`)).toEqual([
      'GET /api/sessions/s/config',
      'PUT /api/sessions/s/config',
      'POST /api/sessions/s/duplicate',
      'POST /api/sessions/s/fork',
      'POST /api/sessions/s/truncate',
      'DELETE /api/sessions/s',
      'POST /api/sessions/L/close',
      'GET /api/config/providers',
      'GET /api/config/harness/qwen/models',
      'GET /api/config/voice/google/models?endpoint=vertex',
      'GET /api/skills',
      'GET /api/agents',
      'GET /api/mcp/servers',
      'GET /api/mcp/servers/chrome%20devtools',
      'GET /api/orchestrator/models',
      'GET /api/orchestrator/models/audio',
      'GET /api/orchestrator/voice/models',
      'POST /api/orchestrator/voice/session?provider=openai&model=m&voice=v&transcription_language=&endpoint=',
      'GET /api/memory/tree',
      'GET /api/visualizations',
      'PATCH /api/visualizations/rename',
      'POST /api/visualizations/cast',
      'GET /api/auth/status',
      'POST /api/auth/login',
      'POST /api/auth/credentials',
      'GET /api/debug/log',
    ]);
    expect(h.fetch.requests.some((r) => r.path.includes('openai-key'))).toBe(false);
  });
});

describe('cast probe (BX-2)', () => {
  it('available', async () => {
    h.fetch.on('GET', '/api/visualizations/cast', { available: true });
    expect(await probeCast()).toBe(true);
    expect(capabilitiesStore.getState()).toMatchObject({ castAvailable: true, castReason: null });
  });
  it('unavailable with a reason', async () => {
    h.fetch.on('GET', '/api/visualizations/cast', { available: false, reason: 'No TV connected' });
    expect(await probeCast()).toBe(false);
    expect(capabilitiesStore.getState().castReason).toBe('No TV connected');
  });
  it('HTML SPA fallback (older backend) → hidden', async () => {
    h.fetch.on('GET', '/api/visualizations/cast', () => textResponse('<!doctype html>', 200, 'text/html'));
    expect(await probeCast()).toBe(false);
  });
  it('404 → hidden', async () => {
    expect(await probeCast()).toBe(false);
    expect(capabilitiesStore.getState().castAvailable).toBe(false);
  });
  it('cast returns the server message; failures keep the detail', async () => {
    h.fetch.on('POST', '/api/visualizations/cast', (r) =>
      (r.body as { path: string }).path === 'x.html' ? jsonResponse({ ok: true, message: 'Showing on TV' }) : jsonResponse({ detail: 'Unknown visualization' }, 404),
    );
    await expect(castVisualization('x.html')).resolves.toEqual({ ok: true, message: 'Showing on TV' });
    await expect(castVisualization('y.html')).rejects.toThrow('Unknown visualization');
  });
  it('audio models probe', async () => {
    h.fetch.on('GET', '/api/orchestrator/models', { models: [{ model_id: 'gpt-audio', supports_audio: true }], audio_capable_models: ['gpt-audio'], default_model: 'x' });
    await probeAudioModels();
    expect(capabilitiesStore.getState().audioCapableModels).toEqual(['gpt-audio']);
    h.fetch.on('GET', '/api/orchestrator/models', () => jsonResponse({ detail: 'x' }, 500));
    await probeAudioModels();
    expect(capabilitiesStore.getState().audioCapableModels).toEqual(['gpt-audio']);
  });
});

describe('catalog and config services', () => {
  it('lists: items on success; on failure the previous items stay and the error is verbatim', async () => {
    h.fetch.on('GET', '/api/sessions', [{ session_id: 's1', title: 'One' }]);
    await refreshSessionList();
    expect(catalogStore.getState().sessions.items).toHaveLength(1);
    h.fetch.on('GET', '/api/sessions', () => jsonResponse({ detail: 'store busy' }, 503));
    await refreshSessionList();
    expect(catalogStore.getState().sessions).toMatchObject({ error: 'store busy', loading: false });
    expect(catalogStore.getState().sessions.items).toHaveLength(1);
    h.fetch.on('GET', '/api/memory/tree', [{ name: 'a.md', path: 'a.md', is_dir: false, children: null }]);
    await refreshMemoryTree();
    expect(catalogStore.getState().memory.items).toHaveLength(1);
    h.fetch.on('GET', '/api/memory/tree', () => jsonResponse({ detail: 'x' }, 500));
    await refreshMemoryTree();
    expect(catalogStore.getState().memory.error).toBe('x');
    h.fetch.on('GET', '/api/visualizations', () => jsonResponse({ detail: 'v' }, 500));
    await refreshVisuals();
    expect(catalogStore.getState().visuals.error).toBe('v');
  });

  it('rename is optimistic, tolerates 404 and rolls back on other errors', async () => {
    h.fetch.on('GET', '/api/sessions', [{ session_id: 's1', title: 'Old' }]);
    await refreshSessionList();
    h.fetch.on('PATCH', '/api/sessions/s1/rename', () => jsonResponse({ detail: 'title is required' }, 400));
    await expect(renameSession('s1', ' ')).rejects.toThrow('title is required');
    expect(catalogStore.getState().sessions.items[0]?.title).toBe('Old');
    h.fetch.on('PATCH', '/api/sessions/s1/rename', () => jsonResponse({ detail: 'nf' }, 404));
    await renameSession('s1', 'New');
    expect(catalogStore.getState().sessions.items[0]?.title).toBe('New');
  });

  it('visualization rename and duplicate', async () => {
    h.fetch.on('GET', '/api/visualizations', [{ path: 'a.html', url: '/a.html', title: 'A', created: '', modified: '', size: 1 }]);
    await refreshVisuals();
    h.fetch.on('PATCH', '/api/visualizations/rename', () => jsonResponse({ detail: 'path is required' }, 400));
    await expect(renameVisualization('a.html', 'B')).rejects.toThrow('path is required');
    expect(catalogStore.getState().visuals.items[0]?.title).toBe('A');
    h.fetch.on('PATCH', '/api/visualizations/rename', () => new Response(null, { status: 204 }));
    await renameVisualization('a.html', 'B');
    h.fetch.on('POST', '/api/sessions/s1/duplicate', () => jsonResponse({ session_id: 'copy' }, 201));
    await expect(duplicateSession('s1')).resolves.toBe('copy');
  });

  it('config save: returned object replaces the copy, "Saved"; a 400 shows the detail verbatim with Retry', async () => {
    h.fetch.on('GET', '/api/config', { provider: 'claude' });
    await loadServerConfig();
    expect(serverConfigStore.getState().config?.provider).toBe('claude');
    h.fetch.on('PUT', '/api/config', (r) => jsonResponse({ provider: (r.body as { provider: string }).provider, extra: 1 }));
    await saveServerConfig({ provider: 'qwen' });
    expect(serverConfigStore.getState().config).toEqual({ provider: 'qwen', extra: 1 });
    expect(snackbarStore.getState().queue.at(-1)?.message).toBe('Saved');
    h.fetch.on('PUT', '/api/config', () => jsonResponse({ detail: 'voice_vad_threshold must be between 0.15 and 0.5' }, 400));
    await expect(saveServerConfig({ voice_vad_threshold: 0.9 })).rejects.toBeInstanceOf(ApiError);
    const snack = snackbarStore.getState().queue.at(-1);
    expect(snack).toMatchObject({ message: 'voice_vad_threshold must be between 0.15 and 0.5', tone: 'error' });
    expect(serverConfigStore.getState()).toMatchObject({ saving: null, saveError: 'voice_vad_threshold must be between 0.15 and 0.5' });
    h.fetch.on('PUT', '/api/config', { provider: 'qwen', voice_vad_threshold: 0.3 });
    snack?.action?.run();
    await vi.waitFor(() => expect(serverConfigStore.getState().saveError).toBeNull());
    h.fetch.on('GET', '/api/config', () => jsonResponse({ detail: 'broken' }, 500));
    await loadServerConfig();
    expect(serverConfigStore.getState().error).toBe('broken');
  });

  it('catalogs load independently', async () => {
    h.fetch.on('GET', '/api/config/providers', { providers: [{ id: 'claude', label: 'Claude Code' }] });
    h.fetch.on('GET', '/api/skills', { skills: [{ name: 's', description: '' }] });
    h.fetch.on('GET', '/api/config/voice/google/models', { models: [{ id: 'g' }] });
    await loadConfigCatalogs();
    await loadGoogleVoiceModels('aistudio');
    const s = serverConfigStore.getState();
    expect(s.providers).toHaveLength(1);
    expect(s.skills).toHaveLength(1);
    expect(s.agents).toBeNull();
    expect(s.googleVoiceModels).toEqual({ aistudio: [{ id: 'g' }] });
    h.fetch.on('GET', '/api/config/voice/google/models', () => jsonResponse({}, 500));
    await loadGoogleVoiceModels('vertex');
    expect(serverConfigStore.getState().googleVoiceModels.vertex).toBeUndefined();
  });
});
