/** W-07 pure modules: hash routes (spec 13 §4.5), keyboard table (§4.3, P-7), tab status summaries (§4.2). */
import { describe, expect, it } from 'vitest';
import { initialConversation, type Conversation } from '@/protocol';
import { isTypingTarget, matchAppCommand } from '../keyboard/bindings';
import { formatRoute, parseHash, type Route } from '../navigation/route';
import { screensFor } from '../shell/ScreenLayer';
import { countersText, summarize } from '../workspace/tabSummary';

describe('hash routes', () => {
  const cases: [string, Route][] = [
    ['#/', { name: 'workspace' }],
    ['#/history', { name: 'history' }],
    ['#/memory', { name: 'memory', path: null }],
    ['#/memory/assistant/architecture/voice%20subsystem.md', { name: 'memory', path: 'assistant/architecture/voice subsystem.md' }],
    ['#/visuals/energy/index.html', { name: 'visuals', path: 'energy/index.html' }],
    ['#/settings', { name: 'settings', page: null }],
    ['#/settings/appearance', { name: 'settings', page: 'appearance' }],
  ];
  it.each(cases)('%s round-trips', (hash, route) => {
    expect(parseHash(hash)).toEqual(route);
    expect(formatRoute(route)).toBe(hash);
  });
  it('unknown or empty hashes are the workspace', () => {
    expect(parseHash('')).toEqual({ name: 'workspace' });
    expect(parseHash('#/nope/x')).toEqual({ name: 'workspace' });
  });
  it('screens per size class: compact stacks list + document; wide classes only get Settings', () => {
    const doc: Route = { name: 'memory', path: 'a/b.md' };
    expect(screensFor(doc, 'compact').map((s) => s.key)).toEqual(['memory', 'memory:a/b.md']);
    expect(screensFor(doc, 'expanded')).toEqual([]);
    expect(screensFor({ name: 'history' }, 'medium')).toEqual([]);
    expect(screensFor({ name: 'settings', page: null }, 'expanded').map((s) => s.key)).toEqual(['settings']);
  });
});

describe('keyboard table (P-7)', () => {
  const ev = (key: string, code: string, mods: { ctrl?: boolean; alt?: boolean; shift?: boolean; meta?: boolean } = {}) => ({
    key,
    code,
    ctrlKey: !!mods.ctrl,
    altKey: !!mods.alt,
    shiftKey: !!mods.shift,
    metaKey: !!mods.meta,
  });
  const tab = { standalone: false, typing: false };
  const pwa = { standalone: true, typing: false };

  it('browser tab: Ctrl+Alt bindings', () => {
    expect(matchAppCommand(ev('ArrowRight', 'ArrowRight', { ctrl: true, alt: true }), tab)).toEqual({ type: 'tab', shortcut: { type: 'next' } });
    expect(matchAppCommand(ev('ArrowLeft', 'ArrowLeft', { ctrl: true, alt: true }), tab)).toEqual({ type: 'tab', shortcut: { type: 'prev' } });
    expect(matchAppCommand(ev('∑', 'KeyW', { ctrl: true, alt: true }), tab)).toEqual({ type: 'tab', shortcut: { type: 'close' } });
    expect(matchAppCommand(ev('¡', 'Digit1', { ctrl: true, alt: true }), tab)).toEqual({ type: 'tab', shortcut: { type: 'goto', index: 0 } });
    expect(matchAppCommand(ev('9', 'Digit9', { ctrl: true, alt: true }), tab)).toEqual({ type: 'tab', shortcut: { type: 'last' } });
    expect(matchAppCommand(ev('n', 'KeyN', { ctrl: true, alt: true }), tab)).toEqual({ type: 'newArchie' });
    expect(matchAppCommand(ev('N', 'KeyN', { ctrl: true, alt: true, shift: true }), tab)).toEqual({ type: 'newAgent' });
  });
  it('browser-reserved keys only in an installed window', () => {
    expect(matchAppCommand(ev('Tab', 'Tab', { ctrl: true }), tab)).toBeNull();
    expect(matchAppCommand(ev('Tab', 'Tab', { ctrl: true }), pwa)).toEqual({ type: 'tab', shortcut: { type: 'next' } });
    expect(matchAppCommand(ev('Tab', 'Tab', { ctrl: true, shift: true }), pwa)).toEqual({ type: 'tab', shortcut: { type: 'prev' } });
    expect(matchAppCommand(ev('w', 'KeyW', { ctrl: true }), pwa)).toEqual({ type: 'tab', shortcut: { type: 'close' } });
    expect(matchAppCommand(ev('3', 'Digit3', { ctrl: true }), pwa)).toEqual({ type: 'tab', shortcut: { type: 'goto', index: 2 } });
    expect(matchAppCommand(ev('w', 'KeyW', { ctrl: true }), tab)).toBeNull();
  });
  it('Ctrl+K focuses the conversation search', () => {
    expect(matchAppCommand(ev('k', 'KeyK', { ctrl: true }), tab)).toEqual({ type: 'focusSearch' });
    expect(matchAppCommand(ev('k', 'KeyK', { ctrl: true }), { standalone: false, typing: true })).toEqual({ type: 'focusSearch' });
  });
  it('`/` focuses the composer unless typing', () => {
    expect(matchAppCommand(ev('/', 'Slash'), tab)).toEqual({ type: 'focusComposer' });
    expect(matchAppCommand(ev('/', 'Slash'), { standalone: false, typing: true })).toBeNull();
    const input = document.createElement('input');
    expect(isTypingTarget(input)).toBe(true);
    input.type = 'checkbox';
    expect(isTypingTarget(input)).toBe(false);
    expect(isTypingTarget(document.createElement('textarea'))).toBe(true);
    expect(isTypingTarget(document.createElement('button'))).toBe(false);
  });
});

describe('tab status summary (spec 13 §4.2)', () => {
  const base = (): Conversation => ({ ...initialConversation({ localId: 'x', kind: 'agent' }), conn: 'subscribed', status: 'idle' });
  it('maps status and connection to indicator + label', () => {
    expect(summarize(base(), false)).toMatchObject({ indicator: 'idle', label: 'Ready', busy: false });
    expect(summarize({ ...base(), status: 'thinking' }, false)).toMatchObject({ indicator: 'working', label: 'Thinking…', busy: true });
    expect(summarize({ ...base(), status: 'connecting' }, false)).toMatchObject({ indicator: 'working', label: 'Connecting…' });
    expect(summarize({ ...base(), conn: 'failed' }, false)).toMatchObject({ indicator: 'disconnected', label: "Couldn't connect" });
    expect(summarize({ ...base(), conn: 'offline' }, false)).toMatchObject({ indicator: 'disconnected', label: 'Disconnected' });
    expect(summarize({ ...base(), stall: { elapsed_seconds: 120, last_tool_name: 'Bash', last_tool_use_id: 't' } }, false)).toMatchObject({ indicator: 'warning' });
    expect(summarize({ ...base(), status: 'stopped' }, false)).toMatchObject({ indicator: 'off' });
    expect(summarize(base(), true)).toMatchObject({ indicator: 'off', label: 'Read-only' });
    const tool: Conversation = {
      ...base(),
      status: 'tool_use',
      entries: [
        {
          id: 'e1',
          kind: 'assistant',
          blocks: [
            { id: 'b1', type: 'tool', tool_use_id: 't1', tool_name: 'Bash', tool_input: {}, status: 'running', output: null, scope: 'turn', origin: 'live' },
          ],
        },
      ],
    };
    expect(summarize(tool, false)).toMatchObject({ indicator: 'working', label: 'Using Bash…' });
    const perm: Conversation = {
      ...base(),
      entries: [
        {
          id: 'e1',
          kind: 'assistant',
          blocks: [
            {
              id: 'p1',
              type: 'permission',
              request_id: 'r1',
              tool_name: 'ExitPlanMode',
              tool_input: {},
              state: 'pending',
              responder: null,
              message: null,
              scope: 'turn',
              origin: 'live',
            },
          ],
        },
      ],
    };
    expect(summarize(perm, false)).toMatchObject({ indicator: 'warning', label: 'Waiting for approval' });
  });
  it('counters text: turns · cost · context', () => {
    const s = summarize({ ...base(), counters: { turns: 14, cost: 0.8212, contextTokens: 84000, contextWindow: 200000 } }, false);
    expect(countersText(s)).toBe('14 turns · $0.82 · context 42%');
  });
});
