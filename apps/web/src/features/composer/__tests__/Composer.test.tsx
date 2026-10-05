/**
 * Composer (W-11 DoD) against the real runtimes over a fake socket: the morphing primary button,
 * Enter / Shift+Enter, queued send while working (tray), Stop, deny-with-feedback, the context
 * ring (thresholds, tap → compact, disabled while busy), P-5 voice-message gating, draft
 * persistence, uploads (progress, 413), audio messages (MediaRecorder and the WAV fallback), the
 * read-only Resume bar, and the Voice hook for W-12.
 */
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { configureServices, openSession, startServices, type ArchieRuntime, type SessionRuntime } from '@/services';
import { capabilitiesStore, createSessionStore, draftKey, patchCapabilities, setFrameScheduler } from '@/stores';
import { initialConversation } from '@/protocol';
import type { ClientCapabilities } from '@/platform';
import { expectNoAxeViolations } from '@/test/axe';
import { Composer } from '../Composer';
import { setStartVoiceHandler } from '../voiceHook';
import { EMPTY, FakeWebSocket, jsonResponse, setupServices, teardownServices, type Harness } from '../../../services/__tests__/fakes';

const CHAT = '/api/sessions/chat';
const ORCH = '/api/orchestrator/chat';
let h: Harness;

const CAPS: ClientCapabilities = {
  secureContext: true,
  webAudio: true,
  audioWorklet: true,
  mediaRecorder: true,
  getUserMedia: true,
  webrtc: true,
  clipboardApi: true,
  resizeObserver: true,
  pointerEvents: true,
  touch: false,
  serviceWorker: false,
  randomUUID: true,
  sendBeacon: true,
  simulatedCompat: false,
};

beforeEach(() => {
  h = setupServices();
  // publish on a microtask (tests wrap frames in `await act(async …)`)
  setFrameScheduler({
    schedule: (fn) => {
      let cancelled = false;
      void Promise.resolve().then(() => {
        if (!cancelled) fn();
      });
      return () => {
        cancelled = true;
      };
    },
  });
  patchCapabilities({ client: CAPS, audioCapableModels: ['gpt-audio-mini'] });
  sessionStorage.clear();
});
afterEach(() => {
  teardownServices();
  setStartVoiceHandler(null);
  capabilitiesStore.setState({ client: null, audioCapableModels: [] });
});

function agent(localId = 'A1'): { rt: SessionRuntime; ws: FakeWebSocket } {
  const rt = openSession({ kind: 'agent', localId, focus: true }) as SessionRuntime;
  const ws = FakeWebSocket.last(CHAT);
  ws.open();
  ws.emit({ type: 'session_started', session_id: localId, context_window: 200000 });
  return { rt, ws };
}

function archie(model: Record<string, unknown> = { model: 'claude-sonnet-4-5', supports_audio: false }): { rt: ArchieRuntime; ws: FakeWebSocket } {
  startServices({ skipInitialSync: true });
  const rt = openSession({ kind: 'archie', localId: 'O1', focus: true }) as ArchieRuntime;
  const ws = FakeWebSocket.last(ORCH);
  ws.open();
  ws.emit({ type: 'session_started', session_id: 'O1', jsonl_id: 'O1', model_info: model });
  return { rt, ws };
}

const sent = (ws: FakeWebSocket) => ws.messages().filter((m) => m.type !== 'start');
const primary = () => screen.getByRole('button', { name: /^(Start voice|Send|Stop|Send voice message)$/ });

describe('primary button morph (mockups "Composer states")', () => {
  it('Archie: Voice when empty → Send with text → Stop while working and empty', async () => {
    const user = userEvent.setup();
    const voice = vi.fn();
    setStartVoiceHandler(voice);
    const { ws } = archie();
    render(<Composer localId="O1" />);
    expect((primary()).getAttribute('aria-label')).toBe('Start voice');
    await user.click(primary());
    expect(voice).toHaveBeenCalledWith('O1');

    const field = screen.getByRole('textbox', { name: 'Message' });
    expect((field).getAttribute('placeholder')).toBe('Message Archie…');
    await user.type(field, 'Dim the lights');
    expect((primary()).getAttribute('aria-label')).toBe('Send');
    await user.keyboard('{Enter}');
    expect(sent(ws)).toEqual([{ type: 'send', text: 'Dim the lights' }]);
    expect((field as HTMLTextAreaElement).value).toBe('');

    await act(async () => ws.emit({ type: 'status', status: 'streaming' }));
    expect((primary()).getAttribute('aria-label')).toBe('Stop');
    await user.click(primary());
    expect(sent(ws).pop()).toEqual({ type: 'interrupt' });
  });

  it('agent: disabled Send when empty and idle (no voice for agents)', async () => {
    agent();
    render(<Composer localId="A1" />);
    expect((primary()).getAttribute('aria-label')).toBe('Send');
    expect((primary()).getAttribute('aria-disabled')).toBe('true');
  });

  it('Shift+Enter inserts a newline; Enter sends the trimmed text', async () => {
    const user = userEvent.setup();
    const { ws } = agent();
    render(<Composer localId="A1" />);
    const field = screen.getByRole('textbox', { name: 'Message' });
    await user.type(field, 'line one{Shift>}{Enter}{/Shift}line two  ');
    expect((field as HTMLTextAreaElement).value).toBe('line one\nline two  ');
    expect(sent(ws)).toEqual([]);
    await user.keyboard('{Enter}');
    expect(sent(ws)).toEqual([{ type: 'send', text: 'line one\nline two' }]);
  });

  it('has no axe violations', async () => {
    agent();
    const { container } = render(<Composer localId="A1" />);
    await expectNoAxeViolations(container);
  });
});

describe('queued send while working (inv02 F-16, I-12)', () => {
  it('Enter while the agent works queues: a "Queued" chip in the tray, the frame is sent, Stop restores it', async () => {
    const user = userEvent.setup();
    const { rt, ws } = agent();
    render(<Composer localId="A1" />);
    const field = screen.getByRole('textbox', { name: 'Message' });
    await user.type(field, 'first{Enter}');
    await act(async () => ws.emit({ type: 'status', status: 'processing' }));
    expect((field).getAttribute('placeholder')).toBe('Queue a message…');
    await user.type(field, 'then run the tests{Enter}');
    expect(sent(ws)).toEqual([
      { type: 'send', text: 'first' },
      { type: 'send', text: 'then run the tests' },
    ]);
    const tray = screen.getByRole('list', { name: 'Waiting to send' });
    expect(within(tray).getByText('Queued')).toBeTruthy();
    expect(within(tray).getByText('then run the tests')).toBeTruthy();
    expect(rt.conv.queue).toEqual([{ text: 'then run the tests', owner: 'local' }]);

    // §6.3: Stop drops the server queue; the queued text comes back into the empty field
    await user.click(primary());
    expect(sent(ws).pop()).toEqual({ type: 'interrupt' });
    expect((field as HTMLTextAreaElement).value).toBe('then run the tests');
    expect(screen.queryByRole('list', { name: 'Waiting to send' })).toBeNull();
  });

  it('a remote queued prompt shows as "Queued · other device" and leaves the tray on dispatch', async () => {
    const { ws } = agent();
    render(<Composer localId="A1" />);
    await act(async () => {
      ws.emit({ type: 'status', status: 'processing' });
      ws.emit({ type: 'user_message', text: 'from the phone', queued: true });
    });
    expect(screen.getByText('Queued · other device')).toBeTruthy();
    await act(async () => {
      ws.emit({ type: 'turn_complete', session_id: 'SDK1', input_tokens: 10 });
      ws.emit({ type: 'status', status: 'processing' });
    });
    expect(screen.queryByText('from the phone')).toBeNull();
  });
});

describe('deny with feedback (spec 12 §6.9)', () => {
  it('typing while a permission is pending sends the text; the server resolves the request as deny with it', async () => {
    const user = userEvent.setup();
    const { rt, ws } = agent();
    render(<Composer localId="A1" />);
    await act(async () => {
      ws.emit({ type: 'status', status: 'processing' });
      ws.emit({ type: 'tool_use', tool_use_id: 't1', tool_name: 'ExitPlanMode', tool_input: { plan: 'p' } });
      ws.emit({ type: 'permission_request', request_id: 'r1', tool_name: 'ExitPlanMode', tool_input: { plan: 'p' } });
    });
    const field = screen.getByRole('textbox', { name: 'Message' });
    expect((field).getAttribute('placeholder')).toBe('Type to give feedback…');
    await user.type(field, 'Split it in two steps{Enter}');
    expect(sent(ws)).toEqual([{ type: 'send', text: 'Split it in two steps' }]); // no permission_response frame
    await act(async () => ws.emit({ type: 'permission_resolved', request_id: 'r1', decision: 'deny', responder: 'user', message: 'Split it in two steps' }));
    const perm = rt.conv.entries.flatMap((e) => (e.kind === 'assistant' ? e.blocks : [])).find((b) => b.type === 'permission');
    expect(perm && perm.type === 'permission' ? perm.state : null).not.toBe('pending');
  });
});

describe('context ring (spec 12 §6.4, inv02 F-10)', () => {
  const ring = () => screen.getByRole('button', { name: /^Context/ });
  it('shows NN% with caution ≥ 50 % and warning ≥ 80 %; tap → compact; disabled while busy', async () => {
    const user = userEvent.setup();
    const { ws } = agent();
    render(<Composer localId="A1" />);
    await act(async () => ws.emit({ type: 'turn_complete', session_id: 'S', input_tokens: 98_000 }));
    expect((ring()).getAttribute('aria-label')).toBe('Context 49% used. Compact context');
    expect((ring()).getAttribute('data-level')).toBe('normal');
    await act(async () => ws.emit({ type: 'turn_complete', session_id: 'S', input_tokens: 100_000 }));
    expect((ring()).getAttribute('data-level')).toBe('caution');
    await act(async () => ws.emit({ type: 'turn_complete', session_id: 'S', input_tokens: 160_000 }));
    expect((ring()).getAttribute('data-level')).toBe('warning');
    expect((ring()).textContent).toContain('80%');
    await user.click(ring());
    expect(sent(ws).pop()).toEqual({ type: 'compact' });
    // compacting = busy: a second tap does nothing
    expect((ring()).getAttribute('aria-disabled')).toBe('true');
    await user.click(ring());
    expect(sent(ws).filter((m) => m.type === 'compact')).toHaveLength(1);
  });
});

describe('P-5: the voice-message button follows the CURRENT Archie model', () => {
  it('hidden while the model has no audio, shown after model_changed to an audio model', async () => {
    const { ws } = archie({ model: 'claude-sonnet-4-5', supports_audio: false });
    render(<Composer localId="O1" />);
    expect(screen.queryByRole('button', { name: 'Record voice message' })).toBeNull();
    await act(async () => ws.emit({ type: 'model_changed', model_info: { model: 'gpt-audio-mini', supports_audio: true } }));
    expect(screen.getByRole('button', { name: 'Record voice message' })).toBeTruthy();
  });

  it('falls back to the audio-capable list when the session does not say', async () => {
    archie({ model: 'gpt-audio-mini' });
    render(<Composer localId="O1" />);
    expect(screen.getByRole('button', { name: 'Record voice message' })).toBeTruthy();
  });

  it('never on agent sessions', async () => {
    agent();
    render(<Composer localId="A1" />);
    expect(screen.queryByRole('button', { name: 'Record voice message' })).toBeNull();
  });

  it('hidden when the browser can record neither way', async () => {
    patchCapabilities({ client: { ...CAPS, mediaRecorder: false, webAudio: false } });
    archie({ model: 'gpt-audio-mini', supports_audio: true });
    render(<Composer localId="O1" />);
    expect(screen.queryByRole('button', { name: 'Record voice message' })).toBeNull();
  });
});

describe('draft persistence', () => {
  it('writes draft:<local_id> to sessionStorage and a new store for the same id restores it', async () => {
    const user = userEvent.setup();
    agent('D1');
    render(<Composer localId="D1" />);
    await user.type(screen.getByRole('textbox', { name: 'Message' }), 'half a thought');
    await waitFor(() => {
      expect(sessionStorage.getItem(draftKey('D1'))).toBe('half a thought');
    });
    const again = createSessionStore({ localId: 'D1', conv: initialConversation({ localId: 'D1', kind: 'agent' }) });
    expect(again.store.getState().draft).toBe('half a thought');
  });
});

// ───────────────────────── uploads ─────────────────────────

class FakeXhr {
  static last: FakeXhr | null = null;
  status = 0;
  responseText = '';
  timeout = 0;
  headers: Record<string, string> = {};
  upload: { onprogress: ((e: { loaded: number; total: number; lengthComputable: boolean }) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  ontimeout: (() => void) | null = null;
  url = '';
  aborted = false;
  constructor() {
    FakeXhr.last = this;
  }
  open(_m: string, url: string): void {
    this.url = url;
  }
  setRequestHeader(k: string, v: string): void {
    this.headers[k] = v;
  }
  getResponseHeader(k: string): string | null {
    return k.toLowerCase() === 'content-type' ? (this.headers['response-type'] ?? 'application/json') : null;
  }
  send(): void {}
  abort(): void {
    this.aborted = true;
  }
  respond(status: number, body: string, type = 'application/json'): void {
    this.status = status;
    this.responseText = body;
    this.headers['response-type'] = type;
    this.onload?.();
  }
}

describe('uploads (spec 12 §6.15)', () => {
  beforeEach(() => {
    configureServices({ XMLHttpRequest: FakeXhr as never });
  });
  const result = { filename: 'notes.txt', path: '/srv/uploads/notes.txt', url: '/uploads/notes.txt', size: 2048, content_type: 'text/plain' };

  it('agent: progress chip, then the reference line goes into the draft', async () => {
    const user = userEvent.setup();
    agent();
    render(<Composer localId="A1" />);
    await user.click(screen.getByRole('button', { name: 'Attach, voice message or slash command' }));
    await user.click(screen.getByRole('menuitem', { name: 'Attach file' }));
    const input = document.querySelector<HTMLInputElement>('input[type="file"]') as HTMLInputElement;
    await user.upload(input, new File(['hello'], 'notes.txt', { type: 'text/plain' }));
    const xhr = FakeXhr.last as FakeXhr;
    await act(async () => xhr.upload.onprogress?.({ loaded: 50, total: 100, lengthComputable: true }));
    expect(screen.getByText('Uploading 50%')).toBeTruthy();
    await act(async () => xhr.respond(200, JSON.stringify(result)));
    await waitFor(() => {
      expect((screen.getByRole('textbox', { name: 'Message' }) as HTMLTextAreaElement).value).toBe(
        '[shared file] notes.txt (2.0 KB, text/plain) — /uploads/notes.txt\nLocal path: /srv/uploads/notes.txt',
      );
    });
    expect(screen.queryByText(/Uploading/)).toBeNull();
  });

  it('Archie: the uploaded file is injected (inject_text)', async () => {
    const user = userEvent.setup();
    const { ws } = archie();
    render(<Composer localId="O1" />);
    await user.click(screen.getByRole('button', { name: 'Attach, voice message or slash command' }));
    await user.click(screen.getByRole('menuitem', { name: 'Attach file' }));
    await user.upload(document.querySelector<HTMLInputElement>('input[type="file"]') as HTMLInputElement, new File(['x'], 'notes.txt'));
    await act(async () => FakeXhr.last?.respond(200, JSON.stringify(result)));
    await waitFor(() => {
      expect(sent(ws).pop()).toEqual({ type: 'inject_text', text: expect.stringContaining('[shared file] notes.txt') as unknown });
    });
  });

  it('an nginx HTML 413 says the file is too large (G-1) and drops the chip', async () => {
    const user = userEvent.setup();
    const { snackbarStore } = await import('@/stores');
    agent();
    render(<Composer localId="A1" />);
    await user.click(screen.getByRole('button', { name: 'Attach, voice message or slash command' }));
    await user.click(screen.getByRole('menuitem', { name: 'Attach file' }));
    await user.upload(document.querySelector<HTMLInputElement>('input[type="file"]') as HTMLInputElement, new File(['x'], 'big.mov'));
    await act(async () => FakeXhr.last?.respond(413, '<html><body><center><h1>413 Request Entity Too Large</h1></center><hr><center>nginx</center></body></html>', 'text/html'));
    await waitFor(() => {
      expect(snackbarStore.getState().queue.map((q) => q.message).join()).toMatch(/big\.mov: File too large for the server/);
    });
    expect(screen.queryByText(/Uploading/)).toBeNull();
  });

  it('cancel aborts the upload', async () => {
    const user = userEvent.setup();
    agent();
    render(<Composer localId="A1" />);
    await user.click(screen.getByRole('button', { name: 'Attach, voice message or slash command' }));
    await user.click(screen.getByRole('menuitem', { name: 'Attach file' }));
    await user.upload(document.querySelector<HTMLInputElement>('input[type="file"]') as HTMLInputElement, new File(['x'], 'a.txt'));
    await user.click(screen.getByRole('button', { name: 'Cancel upload of a.txt' }));
    expect(FakeXhr.last?.aborted).toBe(true);
    await waitFor(() => {
      expect(screen.queryByText(/Uploading/)).toBeNull();
    });
  });
});

// ───────────────────────── audio messages ─────────────────────────

describe('audio message (spec 12 §6.16)', () => {
  const track = { stop: vi.fn() };
  beforeEach(() => {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn(() => Promise.resolve({ getTracks: () => [track] })) },
    });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('MediaRecorder: record → Send voice message → send_audio{audio, format} on the orchestrator socket', async () => {
    class MR {
      static isTypeSupported = (m: string) => m === 'audio/webm;codecs=opus';
      state = 'inactive';
      mimeType = 'audio/webm;codecs=opus';
      ondataavailable: ((e: { data: Blob }) => void) | null = null;
      onstop: (() => void) | null = null;
      start() {
        this.state = 'recording';
      }
      stop() {
        this.state = 'inactive';
        this.ondataavailable?.({ data: new Blob(['opus!']) });
        this.onstop?.();
      }
    }
    vi.stubGlobal('MediaRecorder', MR);
    const user = userEvent.setup();
    const { ws } = archie({ model: 'gpt-audio-mini', supports_audio: true });
    render(<Composer localId="O1" />);
    await user.click(screen.getByRole('button', { name: 'Record voice message' }));
    expect(await screen.findByText(/^Recording 0:0\d$/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Send voice message' }));
    await waitFor(() => {
      expect(sent(ws).pop()).toEqual({ type: 'send_audio', audio: btoa('opus!'), format: 'webm' });
    });
    expect(track.stop).toHaveBeenCalled();
    expect(screen.getByRole('textbox', { name: 'Message' })).toBeTruthy();
  });

  it('?caps=compat (no MediaRecorder): the WAV fallback sends format "wav"', async () => {
    patchCapabilities({ client: { ...CAPS, mediaRecorder: false, audioWorklet: false, simulatedCompat: true } });
    class Ctx {
      static last: Ctx;
      sampleRate = 16000;
      destination = {};
      proc = { onaudioprocess: null as null | ((e: unknown) => void), connect: vi.fn(), disconnect: vi.fn() };
      constructor() {
        Ctx.last = this;
      }
      resume = () => Promise.resolve();
      close = () => Promise.resolve();
      createMediaStreamSource = () => ({ connect: vi.fn(), disconnect: vi.fn() });
      createScriptProcessor = () => this.proc;
    }
    vi.stubGlobal('AudioContext', Ctx);
    const user = userEvent.setup();
    const { ws } = archie({ model: 'gpt-audio-mini', supports_audio: true });
    render(<Composer localId="O1" />);
    await user.click(screen.getByRole('button', { name: 'Record voice message' }));
    await screen.findByText(/^Recording/);
    await act(async () => Ctx.last.proc.onaudioprocess?.({ inputBuffer: { getChannelData: () => new Float32Array(160) } }));
    await user.click(screen.getByRole('button', { name: 'Send voice message' }));
    await waitFor(() => {
      const last = sent(ws).pop();
      expect(last?.type).toBe('send_audio');
      expect(last?.format).toBe('wav');
    });
  });

  it('a denied microphone shows a message and stays in text mode', async () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn(() => Promise.reject(Object.assign(new Error('no'), { name: 'NotAllowedError' }))) },
    });
    vi.stubGlobal('MediaRecorder', function MediaRecorder() {});
    const { snackbarStore } = await import('@/stores');
    const user = userEvent.setup();
    archie({ model: 'gpt-audio-mini', supports_audio: true });
    render(<Composer localId="O1" />);
    await user.click(screen.getByRole('button', { name: 'Record voice message' }));
    await waitFor(() => {
      expect(snackbarStore.getState().queue.map((q) => q.message)).toContain('Microphone access was denied');
    });
    expect(screen.getByRole('textbox', { name: 'Message' })).toBeTruthy();
  });
});

describe('read-only view (spec 12 H-3)', () => {
  it('replaces the field with a Resume bar', async () => {
    h.fetch.on('GET', /\/api\/sessions\/PAST\/messages/, () => jsonResponse(EMPTY));
    const rt = openSession({ kind: 'agent', sdkId: 'PAST', focus: true, readOnly: true });
    const tabId = rt.localId;
    render(<Composer localId={tabId} />);
    expect(screen.queryByRole('textbox')).toBeNull();
    expect((screen.getByRole('region', { name: 'Read-only conversation' })).textContent).toContain('A past conversation, read only.');
    expect(screen.getByRole('button', { name: 'Resume' })).toBeTruthy();
  });
});
