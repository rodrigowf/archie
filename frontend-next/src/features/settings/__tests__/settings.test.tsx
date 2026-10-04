/**
 * W-13 settings pages against a fake backend: every control's read → commit path (partial PUT,
 * the answer replaces the local copy), sliders commit on release, the verbatim error + Retry
 * snackbar, MCP "all enabled" semantics, working-directory CRUD + validation + server errors,
 * the Google auto-correct, retired Archie models, appearance prefs, layouts and keyboard.
 */
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { connectionStore, prefsStore, setPref, snackbarStore } from '@/stores';
import { expectNoAxeViolations } from '@/test/axe';
import { setupServices, teardownServices, type Harness } from '../../../services/__tests__/fakes';
import { resetAuth } from '@/features/auth';
import { AppearanceEffects, SettingsScreen } from '..';
import { resetSettingsUi } from '../controller';
import SettingsView from '../SettingsView';
import type { SettingsPageId } from '../pages';
import { serveConfig, type ConfigServer } from './fixtures';

let h: Harness;
let srv: ConfigServer;

beforeEach(() => {
  h = setupServices();
  resetSettingsUi();
  resetAuth();
  setPref('theme', 'dark');
  setPref('textSize', 'default');
});
afterEach(() => {
  teardownServices();
  vi.restoreAllMocks();
});

function view(page: SettingsPageId | null, layout: 'two-pane' | 'pushed' = 'two-pane', onNavigate = vi.fn()) {
  const user = userEvent.setup();
  const r = render(<SettingsView page={page} layout={layout} onNavigate={onNavigate} />);
  return { user, onNavigate, ...r };
}

const lastSnack = () => snackbarStore.getState().queue[snackbarStore.getState().queue.length - 1];

async function loaded(): Promise<void> {
  await waitFor(() => expect(screen.queryByText(/Loading server settings/)).toBeNull());
}

describe('Settings home (mockup e)', () => {
  it('shows the IA §7 groups with each row\'s current value; refetches on open (CFG-3)', async () => {
    srv = serveConfig(h.fetch);
    const { container } = view('appearance');
    await waitFor(() => expect(screen.getByText(/OpenAI · gpt-realtime-2 · cedar · Auto language/)).toBeTruthy());
    const nav = screen.getByRole('navigation', { name: 'Settings' });
    const groups = within(nav).getAllByRole('heading', { level: 2 }).map((x) => x.textContent);
    expect(groups[0]).toBe('This device');
    expect(groups[1]).toMatch(/^Archie \(server\)/);
    expect(groups[2]).toBe('About');
    expect(within(nav).getByText('Dark · default text size')).toBeTruthy();
    expect(within(nav).getByText(/OpenAI · GPT Audio Mini · summaries by server default/)).toBeTruthy();
    expect(within(nav).getByText('VAD 0.28 · silence 1800 ms · gain 1.0×')).toBeTruthy();
    expect(within(nav).getByText('Claude Code by default · Chrome on')).toBeTruthy();
    expect(within(nav).getByText('Laptop (Desktop) · 2 directories · 1 over SSH')).toBeTruthy();
    expect(within(nav).getByText('All 3 enabled')).toBeTruthy();
    await waitFor(() => expect(within(nav).getByText('Claude · signed in')).toBeTruthy());
    expect(h.fetch.requests.filter((r) => r.method === 'GET' && r.path === '/api/config')).toHaveLength(1);
    // two-pane: the selected row is marked, the detail pane shows the page with its scope chip
    expect(within(nav).getByRole('button', { name: /Appearance/ }).getAttribute('aria-current')).toBe('page');
    expect(screen.getByRole('heading', { level: 2, name: 'Appearance' })).toBeTruthy();
    expect(container.querySelector('[data-scope="device"]')?.textContent).toMatch(/^This device · /);
    await expectNoAxeViolations(container);
  });

  it('server rows are disabled while offline', async () => {
    srv = serveConfig(h.fetch);
    const { user, onNavigate } = view(null, 'pushed');
    await loaded();
    act(() => connectionStore.setState({ backend: 'offline', lastError: 'x', since: 1 }));
    const row = screen.getByRole('button', { name: /Voice tuning/ });
    expect(row.getAttribute('aria-disabled')).toBe('true');
    expect(within(row).getByText('Offline')).toBeTruthy();
    await user.click(row);
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('a failed load shows the verbatim error with Retry', async () => {
    h.fetch.on('GET', '/api/config', () => new Response(JSON.stringify({ detail: 'config file is locked' }), { status: 500 }));
    const { user } = view('voice');
    expect(await screen.findByText('config file is locked')).toBeTruthy();
    srv = serveConfig(h.fetch);
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('combobox', { name: 'Provider' })).toBeTruthy();
  });
});

describe('keyboard and layouts', () => {
  it('compact/medium: Enter on a row pushes the page; Back and Escape return to the list', async () => {
    srv = serveConfig(h.fetch);
    const { user, onNavigate, rerender } = view(null, 'pushed');
    await loaded();
    screen.getByRole('button', { name: /Voice tuning/ }).focus();
    await user.keyboard('{Enter}');
    expect(onNavigate).toHaveBeenLastCalledWith('voice-tuning');
    rerender(<SettingsView page="voice-tuning" layout="pushed" onNavigate={onNavigate} />);
    const page = await screen.findByRole('region', { name: 'Voice tuning' });
    const back = within(page).getByRole('button', { name: 'Back to Settings' });
    await waitFor(() => expect(document.activeElement).toBe(back));
    // the list underneath is hidden from AT while covered
    expect(screen.getByRole('navigation', { name: 'Settings', hidden: true }).closest('[aria-hidden="true"]')).not.toBeNull();
    await user.keyboard('{Escape}');
    expect(onNavigate).toHaveBeenLastCalledWith(null);
    await user.click(back);
    expect(onNavigate).toHaveBeenCalledTimes(3);
  });

  it('the lazy SettingsScreen entry renders the view', async () => {
    srv = serveConfig(h.fetch);
    render(<SettingsScreen page="about" layout="two-pane" />);
    expect(await screen.findByRole('heading', { level: 2, name: 'About Archie' })).toBeTruthy();
  });
});

describe('Appearance (device-local, spec 12 §8.2)', () => {
  it('theme / text size / switches save to prefs with a "Saved" snackbar; text size scales the root', async () => {
    srv = serveConfig(h.fetch);
    const { user } = view('appearance');
    render(<AppearanceEffects />);
    await user.click(screen.getByRole('radio', { name: /Light/ }));
    expect(prefsStore.getState().theme).toBe('light');
    expect(lastSnack()?.message).toBe('Saved');
    await user.click(screen.getByRole('radio', { name: 'Larger' }));
    expect(prefsStore.getState().textSize).toBe('xlarge');
    await waitFor(() => expect(document.documentElement.style.fontSize).toBe('125%'));
    await user.click(screen.getByRole('switch', { name: 'Group tool steps' }));
    expect(prefsStore.getState().toolStepGrouping).toBe(false);
    expect(h.fetch.calls('PUT', '/api/config')).toHaveLength(0);
    // details behind ⓘ
    const info = screen.getAllByRole('button', { name: 'Details' })[0] as HTMLElement;
    expect(info.getAttribute('aria-expanded')).toBe('false');
    await user.click(info);
    expect(info.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByText(/get this automatically/).closest('[hidden]')).toBeNull();
  });
});

describe('Voice tuning: sliders commit on release (fixes inv02 §6.2)', () => {
  it('dragging does not PUT; release PUTs once and the answer replaces the local copy', async () => {
    srv = serveConfig(h.fetch);
    view('voice-tuning');
    const slider = await screen.findByRole('slider', { name: 'Speech detection threshold' });
    fireEvent.mouseDown(slider);
    for (const v of ['0.3', '0.33', '0.36', '0.41']) fireEvent.input(slider, { target: { value: v } });
    expect(srv.puts()).toHaveLength(0);
    expect(screen.getAllByText('0.41').length).toBeGreaterThan(0); // live value next to the label
    fireEvent.change(slider);
    fireEvent.mouseUp(window);
    await waitFor(() => expect(srv.puts()).toEqual([{ voice_vad_threshold: 0.41 }]));
    await waitFor(() => expect(lastSnack()?.message).toBe('Saved'));
    expect(screen.getByRole('navigation', { name: 'Settings' }).textContent).toContain('VAD 0.41');
  });

  it('a rejected save shows the backend detail verbatim with Retry, which re-sends the same PUT', async () => {
    let fail = true;
    srv = serveConfig(h.fetch, { reject: () => (fail ? { status: 400, detail: 'voice_vad_min_silence_ms must be in [800, 5000]' } : null) });
    view('voice-tuning');
    const slider = await screen.findByRole('slider', { name: 'Pause before replying' });
    fireEvent.input(slider, { target: { value: '2500' } });
    fireEvent.change(slider);
    await waitFor(() => expect(lastSnack()?.tone).toBe('error'));
    const snack = lastSnack();
    expect(snack?.message).toBe('voice_vad_min_silence_ms must be in [800, 5000]');
    expect(snack?.action?.label).toBe('Retry');
    // the slider snaps back to the server value
    await waitFor(() => expect((slider as HTMLInputElement).value).toBe('1800'));
    fail = false;
    act(() => snack?.action?.run());
    await waitFor(() => expect(srv.config.voice_vad_min_silence_ms).toBe(2500));
    expect(srv.puts()).toEqual([{ voice_vad_min_silence_ms: 2500 }, { voice_vad_min_silence_ms: 2500 }]);
  });
});

describe('MCP servers (CFG-4)', () => {
  it('[] shows every switch on; switching one off writes the others; Turn all on writes []', async () => {
    srv = serveConfig(h.fetch);
    const { user } = view('mcp-servers');
    const sw = await screen.findAllByRole('switch');
    expect(sw.map((s) => s.getAttribute('aria-checked'))).toEqual(['true', 'true', 'true']);
    expect(screen.getByText(/All servers are on/)).toBeTruthy();
    await user.click(screen.getByRole('switch', { name: 'filesystem' }));
    await waitFor(() => expect(srv.puts()).toEqual([{ enabled_mcps: ['chrome-devtools', 'github'] }]));
    await waitFor(() => expect(screen.getByRole('switch', { name: 'filesystem' }).getAttribute('aria-checked')).toBe('false'));
    await user.click(screen.getByRole('button', { name: 'Turn all on' }));
    await waitFor(() => expect(srv.puts()[1]).toEqual({ enabled_mcps: [] }));
  });

  it('the last enabled server cannot be switched off', async () => {
    srv = serveConfig(h.fetch, { config: { enabled_mcps: ['github'] } });
    view('mcp-servers');
    const gh = await screen.findByRole('switch', { name: 'github' });
    expect(gh.getAttribute('aria-disabled')).toBe('true');
    expect(screen.getByText(/Last one on/)).toBeTruthy();
  });
});

describe('Working directories (F-33, CFG-7)', () => {
  it('selecting a row PUTs working_directory', async () => {
    srv = serveConfig(h.fetch);
    const { user } = view('working-directories');
    await user.click(await screen.findByRole('radio', { name: /Jetson \(local\)/ }));
    await waitFor(() => expect(srv.puts()).toEqual([{ working_directory: '/home/rodrigo/assistant' }]));
  });

  it('add over SSH: validation, computed host:path id, full-history PUT, new entry active', async () => {
    srv = serveConfig(h.fetch);
    const { user } = view('working-directories');
    await user.click(await screen.findByRole('button', { name: 'Add directory' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add directory' });
    await user.click(within(dialog).getByRole('radio', { name: /Over SSH/ }));
    const add = within(dialog).getByRole('button', { name: 'Add' });
    expect(add.getAttribute('aria-disabled')).toBe('true'); // required fields missing
    await user.type(within(dialog).getByRole('textbox', { name: /SSH host/ }), 'rodrigo@10.0.0.5');
    await user.type(within(dialog).getByRole('textbox', { name: /Remote path/ }), 'proj');
    await user.click(add);
    expect(within(dialog).getByText('Put the user in the User field')).toBeTruthy();
    expect(within(dialog).getByText(/Use an absolute path/)).toBeTruthy();
    expect(srv.puts()).toHaveLength(0);
    const host = within(dialog).getByRole('textbox', { name: /SSH host/ });
    await user.clear(host);
    await user.type(host, '10.0.0.5');
    await user.type(within(dialog).getByRole('textbox', { name: /^User/ }), 'rodrigo');
    const path = within(dialog).getByRole('textbox', { name: /Remote path/ });
    await user.clear(path);
    await user.type(path, '/srv/proj{Enter}');
    await waitFor(() => expect(srv.puts()).toHaveLength(1));
    const body = srv.puts()[0] as { working_directory: string; working_directory_history: { id: string }[] };
    expect(body.working_directory).toBe('10.0.0.5:/srv/proj');
    expect(body.working_directory_history.map((e) => e.id)).toEqual([
      '/home/rodrigo/assistant',
      '192.168.0.28:/home/rodrigo/assistant',
      '10.0.0.5:/srv/proj',
    ]);
    expect(body.working_directory_history[2]).toMatchObject({ ssh_host: '10.0.0.5', ssh_user: 'rodrigo', claude_config_dir: null });
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Add directory' })).toBeNull());
  });

  it('a local path that does not exist: the 400 detail shows verbatim and the dialog stays open', async () => {
    srv = serveConfig(h.fetch, {
      reject: (b) => (b.working_directory_history ? { status: 400, detail: 'Directory does not exist: /nope' } : null),
    });
    const { user } = view('working-directories');
    await user.click(await screen.findByRole('button', { name: 'Add directory' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add directory' });
    await user.type(within(dialog).getByRole('textbox', { name: /^Path/ }), '/nope{Enter}');
    expect(await within(dialog).findByText('Directory does not exist: /nope')).toBeTruthy();
    expect(lastSnack()?.message).toBe('Directory does not exist: /nope');
  });

  it('edit keeps a pinned CLAUDE_CONFIG_DIR, re-derives an automatic one; delete confirms; the only entry cannot go', async () => {
    srv = serveConfig(h.fetch);
    const { user } = view('working-directories');
    await user.click(await screen.findByRole('button', { name: 'Edit Laptop (Desktop)' }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit directory' });
    const path = within(dialog).getByRole('textbox', { name: /Remote path/ });
    await user.clear(path);
    await user.type(path, '/home/rodrigo/other');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(srv.puts()).toHaveLength(1));
    const edited = (srv.puts()[0] as { working_directory_history: Record<string, unknown>[]; working_directory: string });
    expect(edited.working_directory).toBe('192.168.0.28:/home/rodrigo/other');
    expect(edited.working_directory_history[1]).toMatchObject({ id: '192.168.0.28:/home/rodrigo/other', claude_config_dir: null });

    await user.click(await screen.findByRole('button', { name: 'Remove Jetson (local)' }));
    const confirm = await screen.findByRole('alertdialog');
    await user.click(within(confirm).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(srv.puts()).toHaveLength(2));
    expect((srv.puts()[1] as { working_directory_history: unknown[] }).working_directory_history).toHaveLength(1);
    await waitFor(() => expect(screen.getByRole('button', { name: /the only directory can't be removed/ }).getAttribute('aria-disabled')).toBe('true'));
  });
});

describe('Voice (CFG-5, CFG-6)', () => {
  it('Google: a stale saved model is switched once to the discovered default, with a dismissible notice', async () => {
    srv = serveConfig(h.fetch, { config: { default_voice_provider: 'google', default_voice_model: 'gemini-live-2.5-flash-native-audio', default_voice_name: 'Kore' } });
    const { user } = view('voice');
    expect(await screen.findByText(/gemini-live-2.5-flash-native-audio is no longer available from Google. Switched to gemini-3.5-transcribe-live/)).toBeTruthy();
    expect(srv.puts()).toEqual([{ default_voice_model: 'gemini-3.5-transcribe-live', default_voice_name: 'Kore' }]);
    expect(h.fetch.calls('GET', '/api/config/voice/google/models?endpoint=aistudio').length).toBeGreaterThan(0);
    await user.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByText(/no longer available/)).toBeNull();
  });

  it('provider change PUTs only the provider and re-renders from the cascaded answer', async () => {
    srv = serveConfig(h.fetch, {
      reject: () => null,
    });
    h.fetch.on('PUT', '/api/config', (req) => {
      const body = req.body as Record<string, unknown>;
      srv.config = { ...srv.config, ...body, default_voice_model: 'qwen3.5-omni-plus-realtime', default_voice_name: 'Aiden', default_voice_transcription_language: 'en' };
      return new Response(JSON.stringify(srv.config), { status: 200, headers: { 'content-type': 'application/json' } });
    });
    const { user } = view('voice');
    await user.click(await screen.findByRole('combobox', { name: 'Provider' }));
    await user.click(await screen.findByRole('option', { name: 'Qwen (Alibaba)' }));
    await waitFor(() => expect(srv.puts()).toEqual([{ default_voice_provider: 'qwen' }]));
    expect(await screen.findByRole('combobox', { name: 'Transcription language' })).toBeTruthy();
    expect(screen.getByRole('combobox', { name: 'Voice' }).textContent).toContain('Aiden');
  });
});

describe('Conversation model (P-9, O-7)', () => {
  it('a retired saved model is flagged; switching provider saves its first model', async () => {
    srv = serveConfig(h.fetch, { config: { default_model: 'gpt-4o-audio-preview' } });
    const { user } = view('conversation-model');
    expect(await screen.findByText('This model was retired')).toBeTruthy();
    await user.click(screen.getByRole('combobox', { name: 'Provider' }));
    await user.click(await screen.findByRole('option', { name: 'OpenAI' }));
    await waitFor(() => expect(srv.puts()).toEqual([{ default_model: 'gpt-audio-mini' }]));
    await waitFor(() => expect(screen.queryByText('This model was retired')).toBeNull());
  });
});

describe('Agent sessions', () => {
  it('harness, qwen model (shallow harness_model PUT) and the Chrome flag', async () => {
    srv = serveConfig(h.fetch);
    const { user } = view('agent-sessions');
    await user.click(await screen.findByRole('combobox', { name: 'Default harness' }));
    await user.click(await screen.findByRole('option', { name: 'Qwen Code' }));
    await waitFor(() => expect(srv.puts()[0]).toEqual({ provider: 'qwen' }));
    await user.click(await screen.findByRole('combobox', { name: 'Qwen model' }));
    await user.click(await screen.findByRole('option', { name: /qwen3\.6-plus/ }));
    await waitFor(() => expect(srv.puts()[1]).toEqual({ harness_model: { qwen: 'qwen3.6-plus' } }));
    expect(srv.config.harness_model).toEqual({ claude: '', qwen: 'qwen3.6-plus' });
    await user.click(screen.getByRole('switch', { name: 'Claude in Chrome' }));
    await waitFor(() => expect(srv.puts()[2]).toEqual({ chrome_extension: false }));
  });
});

describe('Account and About', () => {
  it('Account: status from /api/auth/status, Check again, and replacing credentials while signed in', async () => {
    srv = serveConfig(h.fetch);
    const { user } = view('account');
    const status = await screen.findByText('Signed in');
    expect(status.getAttribute('data-auth-state')).toBe('in');
    expect(screen.getByText('Paste credentials (no screen on the server)')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Check again' }));
    await waitFor(() => expect(h.fetch.calls('GET', '/api/auth/status').length).toBeGreaterThanOrEqual(2));
    await user.click(screen.getByRole('button', { name: /Replace credentials/ }));
    expect(screen.getByRole('textbox', { name: 'Credentials JSON' })).toBeTruthy();
  });

  it('Account: signed out on a headless server offers the paste flow', async () => {
    srv = serveConfig(h.fetch);
    h.fetch.on('GET', '/api/auth/status', { authenticated: false, auth_url: null, headless: true });
    view('account');
    expect(await screen.findByText('Not signed in')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Set credentials' })).toBeTruthy();
  });

  it('About: version, build, backend; remote logging is a device switch', async () => {
    srv = serveConfig(h.fetch);
    const { user } = view('about');
    expect(screen.getByText(__APP_VERSION__)).toBeTruthy();
    const sw = screen.getByRole('switch', { name: 'Remote logging' });
    const before = prefsStore.getState().remoteLogging;
    await user.click(sw);
    expect(prefsStore.getState().remoteLogging).toBe(!before);
    expect(screen.getByRole('button', { name: /Open-source licenses/ })).toBeTruthy();
  });
});
