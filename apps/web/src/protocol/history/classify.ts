/**
 * REST history helpers shared by the reducer (§5.1) and rewind/fork (§6.5).
 * Regexes avoid lookbehind and named groups (Safari 12, spec 13 §2.3).
 */
import type { NoticeKind, UserOrigin } from '../types';

export type ClassifiedLine =
  | { readonly kind: 'user'; readonly text: string; readonly origin: UserOrigin }
  | { readonly kind: 'notice'; readonly notice: NoticeKind; readonly text: string };

const VOICE_RECORDING = /^\[voice, recording: [^\]]*\] ?/;
const AUDIO = /^\[audio:[A-Za-z0-9]+\] ?/;
/** What the backend persists after the `[audio:<fmt>] ` prefix when a voice message had no prompt. */
const AUDIO_NO_PROMPT = '(audio message)';
const COMMAND = /^<(command-name|command-message|command-args|local-command-stdout|local-command-stderr|local-command-caveat)>/;

function user(text: string, origin: UserOrigin): ClassifiedLine {
  return { kind: 'user', text, origin };
}

function notice(n: NoticeKind, text: string): ClassifiedLine {
  return { kind: 'notice', notice: n, text };
}

/** spec 12 §5.1 `classifyUserLine`: a REST user line → user entry (with origin) or notice. */
export function classifyUserLine(text: string): ClassifiedLine {
  if (text.startsWith('[voice] ')) return user(text.slice(8), 'voice');
  let m = VOICE_RECORDING.exec(text);
  if (m) return user(text.slice(m[0].length), 'voice');
  m = AUDIO.exec(text);
  if (m) {
    const prompt = text.slice(m[0].length);
    return user(prompt === AUDIO_NO_PROMPT ? '' : prompt, 'audio');
  }
  if (text.startsWith('[shared file] ') || text.startsWith('[shared text]')) return user(text, 'inject');
  if (text.startsWith('[Request interrupted by user')) return notice('interrupted', '');
  if (text.startsWith('This session is being continued from a previous conversation')) return notice('compaction', text);
  if (text.startsWith('<task-notification>')) return notice('background', text);
  if (COMMAND.test(text)) return notice('command', text);
  return user(text, 'history');
}

/** R-3: strings unchanged, `null`/`undefined` → `""`, anything else → `JSON.stringify`. */
export function normalizeOutput(x: unknown): string {
  if (typeof x === 'string') return x;
  if (x === null || x === undefined) return '';
  try {
    const s = JSON.stringify(x);
    return typeof s === 'string' ? s : String(x);
  } catch {
    return String(x);
  }
}
