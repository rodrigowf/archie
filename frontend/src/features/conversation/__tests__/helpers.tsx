/**
 * Test helpers for the conversation view: fixtures, seeded sessions (store + inert runtime), and
 * the "render order" of a conversation as the DOM shows it vs as the reducer holds it (R4).
 */
import fs from 'node:fs';
import path from 'node:path';
import type { Fixture } from '../../../../mock-server/scenarios.mjs';
import { actionInput, fixtureStart, runInputs } from '../../../protocol/__tests__/harness';
import { coerceFrame, type Conversation, type ConversationInput, type Entry, type SessionKind } from '@/protocol';
import { normalizeToolName } from '@/features/tools';
import { createSessionStore, registerSession, type FrameScheduler, type SessionStoreHandle } from '@/stores';

export function loadFixture(name: string): Fixture {
  const file = path.resolve(process.cwd(), '..', 'shared', 'protocol-fixtures', `${name}.json`);
  return JSON.parse(fs.readFileSync(file, 'utf8')) as Fixture;
}

export interface InertRuntime {
  readonly localId: string;
  readonly calls: string[];
  dispose(): void;
  loadOlder(): Promise<void>;
  retry(): void;
  interrupt(): void;
  reload(): Promise<void>;
  dismissBanner(): void;
}

/** A store holding `conv`, registered with a runtime that only records calls. */
export function seedSession(
  conv: Conversation,
  opts: { readOnly?: boolean; scheduler?: FrameScheduler } = {},
): { handle: SessionStoreHandle; runtime: InertRuntime } {
  const localId = conv.ref.localId;
  const handle = createSessionStore({ localId, conv, readOnly: opts.readOnly === true, ...(opts.scheduler ? { scheduler: opts.scheduler } : {}) });
  const calls: string[] = [];
  const runtime: InertRuntime = {
    localId,
    calls,
    dispose: () => undefined,
    loadOlder: () => {
      calls.push('loadOlder');
      return Promise.resolve();
    },
    retry: () => calls.push('retry'),
    interrupt: () => calls.push('interrupt'),
    reload: () => {
      calls.push('reload');
      return Promise.resolve();
    },
    dismissBanner: () => calls.push('dismissBanner'),
  };
  registerSession(localId, { handle, runtime });
  return { handle, runtime };
}

/**
 * The fixture's inputs (README runner algorithm). Frames go through `coerceFrame` (the decoder's
 * JSON stage): jsdom's ArrayBuffer is another realm's, so the binary path is covered by W-05.
 */
export function fixtureInputsDom(fx: Fixture): ConversationInput[] {
  const out: ConversationInput[] = [];
  const actions = fx.client_actions ?? [];
  for (let i = 0; i <= fx.events.length; i++) {
    for (const a of actions) {
      if (a.at !== i) continue;
      const inp = actionInput(a);
      if (inp) out.push(inp);
    }
    if (i < fx.events.length) {
      const d = coerceFrame(fx.events[i]);
      if (!d.ok) throw new Error(`fixture frame did not decode: ${d.reason}`);
      out.push({ type: 'frame', frame: d.frame });
    }
  }
  return out;
}

/** The fixture's final state (and every intermediate one). */
export function fixtureRun(name: string): ReturnType<typeof runInputs> & { fx: Fixture } {
  const fx = loadFixture(name);
  const s = fixtureStart(fx);
  return { ...runInputs(s.conv, fixtureInputsDom(fx)), fx };
}

/** Render order from the reducer: one token per user entry, notice and visible block. */
export function convSequence(entries: readonly Entry[], kind: SessionKind = 'agent'): string[] {
  const out: string[] = [];
  for (const e of entries) {
    if (e.kind === 'user') out.push(`user:${e.text}`);
    else if (e.kind === 'notice') out.push(`notice:${e.notice}`);
    else
      for (const b of e.blocks) {
        if (b.type === 'text') {
          if (b.text || b.streaming) out.push(`text:${b.text.trim()}`);
        } else if (b.type === 'thinking') {
          if (b.text || b.streaming) out.push('thinking');
        } else if (b.type === 'tool') out.push(`tool:${normalizeToolName(b.tool_name, kind)}`);
        else out.push(`permission:${b.tool_name}`);
      }
  }
  return out;
}

/** Render order from the DOM (same tokens). */
export function domSequence(root: ParentNode): string[] {
  const out: string[] = [];
  root.querySelectorAll<HTMLElement>('[data-entry-id]').forEach((row) => {
    const kind = row.getAttribute('data-kind');
    if (kind === 'user') {
      const t = row.querySelector('[class*="userText"]');
      out.push(`user:${t?.textContent ?? ''}`);
      return;
    }
    if (kind === 'notice') {
      out.push(`notice:${row.getAttribute('data-notice') ?? ''}`);
      return;
    }
    row.querySelectorAll<HTMLElement>('[data-block], [data-tool]').forEach((el) => {
      const tool = el.getAttribute('data-tool');
      if (tool) {
        out.push(`tool:${tool}`);
        return;
      }
      const b = el.getAttribute('data-block');
      if (b === 'text') out.push(`text:${(el.textContent ?? '').trim()}`);
      else if (b === 'thinking') out.push('thinking');
      else if (b === 'permission') out.push(`permission:${el.getAttribute('data-tool-name') ?? ''}`);
    });
  });
  return out;
}

/** Fake scroll metrics on an element (jsdom has no layout). */
export function stubScroll(
  el: HTMLElement,
  m: { scrollHeight: number; clientHeight: number; scrollTop?: number },
): { set(top: number): void; metrics: { scrollHeight: number; clientHeight: number } } {
  const metrics = { scrollHeight: m.scrollHeight, clientHeight: m.clientHeight };
  let top = m.scrollTop ?? 0;
  Object.defineProperty(el, 'scrollHeight', { configurable: true, get: () => metrics.scrollHeight });
  Object.defineProperty(el, 'clientHeight', { configurable: true, get: () => metrics.clientHeight });
  Object.defineProperty(el, 'scrollTop', {
    configurable: true,
    get: () => top,
    set: (v: number) => {
      top = Math.max(0, Math.min(v, metrics.scrollHeight - metrics.clientHeight));
    },
  });
  return {
    set(t: number) {
      top = t;
    },
    metrics,
  };
}
