/**
 * Pure composer decisions (W-11), unit-tested without React.
 *
 * Primary button (IA §6, mockups "Composer states"): Voice (Archie, empty field, voice possible)
 * → Send (any text) → Stop (working and empty). Enter while working sends a *queued* message
 * (inv02 F-16 parity), so a non-empty field is always Send.
 */
import type { ContextUsage } from '@/protocol';

export type PrimaryMode = 'voice' | 'send' | 'send-disabled' | 'stop';

export interface PrimaryInput {
  readonly kind: 'agent' | 'orchestrator';
  readonly hasText: boolean;
  /** A reply is running (spec 12 §2.5 busy, or a turn in flight). */
  readonly working: boolean;
  /** Realtime voice can start here (Archie, capability, not already active anywhere). */
  readonly voiceAvailable: boolean;
}

export function primaryMode(i: PrimaryInput): PrimaryMode {
  if (i.hasText) return 'send';
  if (i.working) return 'stop';
  if (i.kind === 'orchestrator' && i.voiceAvailable) return 'voice';
  return 'send-disabled';
}

export const PRIMARY_LABEL: Record<PrimaryMode, string> = {
  voice: 'Start voice',
  send: 'Send',
  'send-disabled': 'Send',
  stop: 'Stop',
};

/** Context ring tone (spec 12 §6.4: caution ≥ 50 %, warning ≥ 80 %). */
export function ringTone(u: ContextUsage): 'primary' | 'warning' | 'error' {
  return u.level === 'warning' ? 'error' : u.level === 'caution' ? 'warning' : 'primary';
}

/** Placeholder copy: who the message goes to, or that it will queue / give feedback. */
export function placeholderFor(o: { kind: 'agent' | 'orchestrator'; title: string; working: boolean; permissionPending: boolean }): string {
  if (o.permissionPending) return 'Type to give feedback…';
  if (o.working && o.kind === 'agent') return 'Queue a message…';
  if (o.kind === 'orchestrator') return 'Message Archie…';
  if (!o.title) return 'Message the agent…';
  const t = o.title.trim();
  if (t.length <= PLACEHOLDER_TITLE_MAX) return `Message ${t}…`;
  const cut = t.slice(0, PLACEHOLDER_TITLE_MAX);
  const space = cut.lastIndexOf(' ');
  return `Message ${(space > PLACEHOLDER_TITLE_MAX / 2 ? cut.slice(0, space) : cut).trim()}…`; // one line on phones
}

/** Longest session title in the placeholder (keeps it one line on phones). */
export const PLACEHOLDER_TITLE_MAX = 24;

/** Enter sends; Shift+Enter (and IME composition) inserts a newline (inv02 F-16). */
export function isSubmitKey(e: { key: string; shiftKey: boolean; isComposing?: boolean; keyCode?: number }): boolean {
  return e.key === 'Enter' && !e.shiftKey && !e.isComposing && e.keyCode !== 229;
}

/** `m:ss` for the recording timer. */
export function formatClock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${r < 10 ? '0' : ''}${r}`;
}

/** Insert a slash command at the start of the draft (`/name ` + the rest). */
export function withSlashCommand(draft: string, name: string): string {
  const rest = draft.replace(/^\/\S*\s*/, '');
  return `/${name} ${rest}`;
}
