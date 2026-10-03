/**
 * Relative-time formatting without Intl.RelativeTimeFormat (Safari 14+; banned by ESLint) or
 * the `dateStyle` option (Safari 14.1+).
 */
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

function toMs(d: Date | number): number {
  return typeof d === 'number' ? d : d.getTime();
}

/** Whole calendar days between two instants in local time (0 = same day, 1 = `then` was yesterday). */
export function calendarDayDiff(then: Date | number, now: Date | number = Date.now()): number {
  const a = new Date(toMs(then));
  const b = new Date(toMs(now));
  const a0 = new Date(a.getFullYear(), a.getMonth(), a.getDate()).getTime();
  const b0 = new Date(b.getFullYear(), b.getMonth(), b.getDate()).getTime();
  return Math.round((b0 - a0) / DAY);
}

/** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", then a short date. */
export function formatRelativeTime(then: Date | number, now: Date | number = Date.now()): string {
  const diff = toMs(now) - toMs(then);
  if (diff < 45_000) return 'just now';
  if (diff < HOUR) return `${Math.max(1, Math.round(diff / MINUTE))} min ago`;
  const days = calendarDayDiff(then, now);
  if (days === 0) return `${Math.floor(diff / HOUR)} h ago`;
  if (days === 1) return 'yesterday';
  if (days < 7) return `${days} days ago`;
  return formatShortDate(then, now);
}

/** "12 Mar", or "12 Mar 2025" when the year differs from `now`. */
export function formatShortDate(then: Date | number, now: Date | number = Date.now()): string {
  const d = new Date(toMs(then));
  const sameYear = d.getFullYear() === new Date(toMs(now)).getFullYear();
  const opts: Intl.DateTimeFormatOptions = sameYear
    ? { day: 'numeric', month: 'short' }
    : { day: 'numeric', month: 'short', year: 'numeric' };
  return d.toLocaleDateString(undefined, opts);
}

/** "14:05" style local clock time. */
export function formatClockTime(then: Date | number): string {
  const d = new Date(toMs(then));
  const pad = (n: number): string => (n < 10 ? `0${n}` : String(n));
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
