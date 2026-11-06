/**
 * Pure composer decisions (W-11), unit-tested without React.
 *
 * Primary button (IA §6, mockups "Composer states", and `context/public/composer-dictation/`):
 * Voice (Archie, empty field, voice possible) → Dictate (empty field, dictation on) → Send (any
 * text) → Stop (working and empty). Enter while working sends at once — into the running turn
 * (I-16) or queued behind it (I-12, harnesses that cannot steer) — so a non-empty field is
 * always Send.
 *
 * `dictate` is why an agent tab's empty field is no longer a dead disabled Send. Archie keeps
 * `voice` ahead of it: speaking *to* Archie is that tab's whole point, and dictation is still one
 * tap away in the ＋ menu. Where voice is impossible (already live on another device) dictation
 * takes the slot rather than leaving a disabled button.
 */
import type { ContextUsage } from '@/protocol';

export type PrimaryMode = 'voice' | 'dictate' | 'send' | 'send-disabled' | 'stop';

export interface PrimaryInput {
  readonly kind: 'agent' | 'orchestrator';
  readonly hasText: boolean;
  /** A reply is running (spec 12 §2.5 busy, or a turn in flight). */
  readonly working: boolean;
  /** Realtime voice can start here (Archie, capability, not already active anywhere). */
  readonly voiceAvailable: boolean;
  /** Dictation is switched on server-side and this browser can capture audio. */
  readonly dictationAvailable?: boolean;
}

export function primaryMode(i: PrimaryInput): PrimaryMode {
  if (i.hasText) return 'send';
  if (i.working) return 'stop';
  if (i.kind === 'orchestrator' && i.voiceAvailable) return 'voice';
  if (i.dictationAvailable) return 'dictate';
  return 'send-disabled';
}

export const PRIMARY_LABEL: Record<PrimaryMode, string> = {
  voice: 'Start voice',
  dictate: 'Dictate',
  send: 'Send',
  'send-disabled': 'Send',
  stop: 'Stop',
};

/**
 * Can this browser stream dictation (as opposed to record-then-transcribe)?
 *
 * Reads the detected capabilities rather than probing `window` itself, so `?caps=compat` really
 * does exercise the Safari 12 path (spec 13 §2.5, §6.4). Lives here, not beside the socket code,
 * so the composer can answer it without pulling the capture stack into the initial bundle.
 */
export function canDictate(client: { getUserMedia: boolean; webAudio: boolean; audioWorklet: boolean } | null): boolean {
  return !!client && client.getUserMedia && client.webAudio && client.audioWorklet;
}

/**
 * Join the draft that was already in the field with what was just dictated.
 *
 * Dictation appends rather than replaces, so speaking after typing (or dictating twice) reads as
 * one message. Exactly one space between the two halves, whatever whitespace each side carries —
 * OpenAI's transcripts open with a leading space, ElevenLabs' do not.
 */
export function dictatedDraft(base: string, dictated: string): string {
  const head = base.replace(/\s+$/, '');
  const tail = dictated.replace(/^\s+/, '');
  if (!head) return tail;
  if (!tail) return head;
  return `${head} ${tail}`;
}

/** Context ring tone (spec 12 §6.4: caution ≥ 50 %, warning ≥ 80 %). */
export function ringTone(u: ContextUsage): 'primary' | 'warning' | 'error' {
  return u.level === 'warning' ? 'error' : u.level === 'caution' ? 'warning' : 'primary';
}

/** Placeholder copy: who the message goes to, or that it joins the running turn / queues / gives feedback. */
export function placeholderFor(o: {
  kind: 'agent' | 'orchestrator';
  title: string;
  working: boolean;
  permissionPending: boolean;
  /** I-16: the harness takes a message into the running turn. */
  steers?: boolean;
}): string {
  if (o.permissionPending) return 'Type to give feedback…';
  if (o.working && o.kind === 'agent') return o.steers ? 'Add to the running turn…' : 'Queue a message…';
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
