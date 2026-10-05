import { describe, expect, it } from 'vitest';
import type { VoiceModelEntry, WorkingDirectoryEntry } from '@/services';
import {
  addWorkingDirectory,
  coerceWorkingDirectories,
  coerceWorkingDirectory,
  deleteWorkingDirectory,
  draftFromEntry,
  editWorkingDirectory,
  EMPTY_DRAFT,
  enabledMcpNames,
  entryFromDraft,
  googleAutoCorrect,
  harnessModels,
  isMcpEnabled,
  languageOptions,
  mcpOffBlockedReason,
  mcpSummary,
  modelAvailability,
  toggleMcp,
  validateDraft,
  voiceCatalog,
  workingDirectoriesSummary,
  workingDirectoryName,
} from '../logic';

const ALL = ['chrome-devtools', 'filesystem', 'github'];

describe('MCP "all enabled" semantics (CFG-4, fixes inv02 §6.2)', () => {
  it('shows every server on when the list is empty', () => {
    for (const n of ALL) expect(isMcpEnabled([], n)).toBe(true);
    expect(enabledMcpNames([], ALL)).toEqual(ALL);
    expect(mcpSummary([], ALL)).toBe('All 3 enabled');
    expect(mcpSummary([], ['one'])).toBe('All enabled (1 server)');
  });

  it('unchecking one while the list is empty writes the explicit list of the others', () => {
    expect(toggleMcp([], ALL, 'filesystem', false)).toEqual(['chrome-devtools', 'github']);
  });

  it('checking the last missing server writes [] (all)', () => {
    expect(toggleMcp(['chrome-devtools', 'github'], ALL, 'filesystem', true)).toEqual([]);
  });

  it('keeps catalog order, drops stale names, and summarizes partial lists', () => {
    expect(toggleMcp(['github', 'gone'], ALL, 'chrome-devtools', true)).toEqual(['chrome-devtools', 'github']);
    expect(mcpSummary(['github'], ALL)).toBe('1 of 3 enabled');
    expect(mcpSummary([], [])).toBe('None configured');
  });

  it('refuses to switch off the last enabled server ([] would mean all)', () => {
    expect(mcpOffBlockedReason(['github'], ALL, 'github')).toMatch(/last one on/i);
    expect(mcpOffBlockedReason(['github', 'filesystem'], ALL, 'github')).toBeNull();
    expect(mcpOffBlockedReason([], ['only'], 'only')).toMatch(/last one on/i);
  });
});

const LOCAL: WorkingDirectoryEntry = { id: '/srv/a', path: '/srv/a', label: 'A', ssh_host: null, ssh_user: null, ssh_key: null, claude_config_dir: null };
const REMOTE: WorkingDirectoryEntry = {
  id: '10.0.0.2:/home/r/p',
  path: '/home/r/p',
  label: '',
  ssh_host: '10.0.0.2',
  ssh_user: 'r',
  ssh_key: null,
  claude_config_dir: '/home/r/p/.claude_config',
};

describe('working directories: defensive coercion (F-33 [LOAD-BEARING])', () => {
  it('accepts malformed rows without throwing', () => {
    const rows = coerceWorkingDirectories([
      null,
      42,
      '/legacy/string',
      { path: '  ' },
      { path: '/x', label: null, ssh_host: '', ssh_user: 'ignored' },
      { path: '/y', ssh_host: 'h', ssh_user: 7 },
      { id: '', path: '/z', ssh_host: 'h2' },
      LOCAL,
      LOCAL,
    ]);
    expect(rows.map((r) => r.id)).toEqual(['/legacy/string', '/x', 'h:/y', 'h2:/z', '/srv/a']);
    expect(rows[1]).toMatchObject({ label: '', ssh_host: null, ssh_user: null });
    expect(rows[2]).toMatchObject({ ssh_host: 'h', ssh_user: null });
    expect(coerceWorkingDirectories('nope')).toEqual([]);
    expect(coerceWorkingDirectory(undefined)).toBeNull();
  });

  it('names and summaries', () => {
    expect(workingDirectoryName(LOCAL)).toBe('A');
    expect(workingDirectoryName(REMOTE)).toBe('10.0.0.2:/home/r/p');
    expect(workingDirectoriesSummary([LOCAL, REMOTE], '/srv/a')).toBe('A · 2 directories · 1 over SSH');
  });
});

describe('working directories: validation', () => {
  const d = (patch: Partial<typeof EMPTY_DRAFT>) => ({ ...EMPTY_DRAFT, ...patch });
  it('requires a path, an absolute one', () => {
    expect(validateDraft(d({}), [], null).path).toBe('Path is required');
    expect(validateDraft(d({ path: 'rel/dir' }), [], null).path).toMatch(/absolute/);
    expect(validateDraft(d({ path: '~/code' }), [], null)).toEqual({});
  });
  it('SSH needs a host without spaces or user@', () => {
    expect(validateDraft(d({ kind: 'ssh', path: '/p' }), [], null).host).toBe('SSH host is required');
    expect(validateDraft(d({ kind: 'ssh', path: '/p', host: 'r@h' }), [], null).host).toMatch(/User field/);
    expect(validateDraft(d({ kind: 'ssh', path: '/p', host: 'a b' }), [], null).host).toBeTruthy();
    expect(validateDraft(d({ kind: 'ssh', path: '/p', host: 'h', user: 'a b' }), [], null).user).toBeTruthy();
    expect(validateDraft(d({ kind: 'ssh', path: '', host: 'h' }), [], null).path).toBe('Remote path is required');
  });
  it('an edit may not collide with another entry', () => {
    expect(validateDraft(d({ path: '/srv/a' }), [LOCAL, REMOTE], REMOTE.id).path).toMatch(/Another directory/);
    expect(validateDraft(d({ path: '/srv/a' }), [LOCAL, REMOTE], LOCAL.id)).toEqual({});
  });
});

describe('working directories: CRUD (CFG-7)', () => {
  it('add appends and activates; adding an existing one just selects it', () => {
    const r = addWorkingDirectory([LOCAL], { ...EMPTY_DRAFT, kind: 'ssh', host: 'h', user: 'u', path: '/p/', label: ' Lab ' });
    expect(r.active).toBe('h:/p/');
    expect(r.history[1]).toEqual({ id: 'h:/p/', path: '/p/', label: 'Lab', ssh_host: 'h', ssh_user: 'u', ssh_key: null, claude_config_dir: null });
    const again = addWorkingDirectory([LOCAL, REMOTE], { ...EMPTY_DRAFT, path: '/srv/a' });
    expect(again.history).toHaveLength(2);
    expect(again.active).toBe('/srv/a');
  });

  it('edit replaces by the old id, recomputes the SSH id and re-derives an auto CLAUDE_CONFIG_DIR', () => {
    const draft = draftFromEntry(REMOTE);
    expect(draft.configDir).toBe(''); // auto-derived value is not pinned
    const r = editWorkingDirectory([LOCAL, REMOTE], REMOTE.id, { ...draft, host: '10.0.0.9', path: '/new' });
    expect(r.active).toBe('10.0.0.9:/new');
    expect(r.history[1]).toMatchObject({ id: '10.0.0.9:/new', claude_config_dir: null, ssh_user: 'r' });
    const pinned = draftFromEntry({ ...REMOTE, claude_config_dir: '/custom' });
    expect(entryFromDraft(pinned).claude_config_dir).toBe('/custom');
  });

  it('switching an SSH entry to local clears the SSH fields', () => {
    const e = entryFromDraft({ ...draftFromEntry(REMOTE), kind: 'local' });
    expect(e).toMatchObject({ id: '/home/r/p', ssh_host: null, ssh_user: null, ssh_key: null, claude_config_dir: null });
  });

  it('delete never removes the only entry; removing the active one activates the first', () => {
    expect(deleteWorkingDirectory([LOCAL], LOCAL.id, LOCAL.id)).toBeNull();
    expect(deleteWorkingDirectory([LOCAL, REMOTE], REMOTE.id, REMOTE.id)).toEqual({ history: [LOCAL], active: LOCAL.id });
    expect(deleteWorkingDirectory([LOCAL, REMOTE], REMOTE.id, LOCAL.id)?.active).toBe(LOCAL.id);
  });
});

const G = (id: string, extra: Partial<VoiceModelEntry> = {}): VoiceModelEntry => ({
  id,
  label: id,
  voice: 'Puck',
  voices: [
    { id: 'Puck', label: 'Puck' },
    { id: 'Kore', label: 'Kore' },
  ],
  ...extra,
});

describe('Google Gemini Live auto-correct (CFG-6, F-31 [LOAD-BEARING])', () => {
  const cfg = { default_voice_provider: 'google', default_voice_model: 'gemini-live-old', default_voice_name: 'Kore' };

  it('switches a stale saved model to the discovered default, keeping a voice it still offers', () => {
    const fix = googleAutoCorrect(cfg, [G('a'), G('b', { default: true })]);
    expect(fix).toEqual({ from: 'gemini-live-old', to: 'b', patch: { default_voice_model: 'b', default_voice_name: 'Kore' } });
  });

  it("uses the model's default voice when the saved voice is gone, and the first entry when none is default", () => {
    const fix = googleAutoCorrect({ ...cfg, default_voice_name: 'Aoede' }, [G('x'), G('y')]);
    expect(fix?.patch).toEqual({ default_voice_model: 'x', default_voice_name: 'Puck' });
  });

  it('does nothing for other providers, a listed model, or an EMPTY discovered list', () => {
    expect(googleAutoCorrect({ ...cfg, default_voice_provider: 'openai' }, [G('a')])).toBeNull();
    expect(googleAutoCorrect({ ...cfg, default_voice_model: 'a' }, [G('a')])).toBeNull();
    expect(googleAutoCorrect(cfg, [])).toBeNull();
    expect(googleAutoCorrect(cfg, undefined)).toBeNull();
  });

  it('the discovered Google list joins (or replaces) the voice catalog (G-33)', () => {
    const cat = voiceCatalog({ providers: { openai: [G('rt')], google: [G('static')] }, default_provider: 'openai', default_model: 'rt' }, [G('live')]);
    expect(Object.keys(cat)).toEqual(['openai', 'google']);
    expect(cat.google?.map((m) => m.id)).toEqual(['live']);
    expect(voiceCatalog(null, []).google).toBeUndefined();
  });
});

describe('catalog normalisation', () => {
  it('languages: strings or {id,label}; auto-detect first', () => {
    expect(languageOptions(G('m', { transcription_languages: ['en', 'pt'] }))).toEqual([
      { id: '', label: 'Auto-detect' },
      { id: 'en', label: 'en' },
      { id: 'pt', label: 'pt' },
    ]);
    const live = languageOptions(G('m', { transcription_languages: [{ id: '', label: 'Auto-detect', description: 'ASR picks' }, { id: 'pt', label: 'Portuguese' }] as never }));
    expect(live[0]).toEqual({ id: '', label: 'Auto-detect', description: 'ASR picks' });
    expect(live).toHaveLength(2);
    expect(languageOptions(G('m', { transcription_languages: [] }))).toEqual([]);
  });

  it('qwen harness models: ids or objects with traits', () => {
    expect(harnessModels(['a', '', { id: 'b', display_name: 'B', context_window: 1_000_000, supports_thinking: true, supports_vision: true }, { x: 1 }, null])).toEqual([
      { id: 'a', label: 'a', traits: '' },
      { id: 'b', label: 'B', traits: '1000K ctx · thinking · vision' },
    ]);
  });

  it('retired / unknown Archie models (P-9, O-7)', () => {
    const catalog = { models: [{ model_id: 'gpt-audio-mini', provider: 'openai' }], audio_capable_models: [], default_model: 'x' };
    expect(modelAvailability('gpt-4o-audio-preview', catalog)).toBe('retired');
    expect(modelAvailability('nope', catalog)).toBe('unknown');
    expect(modelAvailability('gpt-audio-mini', catalog)).toBe('ok');
    expect(modelAvailability('anything', null)).toBe('ok');
  });
});
