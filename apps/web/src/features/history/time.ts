/**
 * Server timestamps and the history date groups (IA §3, spec 12 A-8.4).
 *
 * The backend sends ISO 8601 with microseconds and a UTC offset
 * (`"2026-08-28T21:49:03.550000+00:00"`, inventory 03 §1.5). Every timestamp is parsed **with its
 * offset** and shown in local time (A-8.4: the old Android client read the first 19 characters as
 * local time and was off by the UTC offset). The parse is by hand rather than `Date.parse`, so the
 * six-digit fraction and the offset forms (`Z`, `+00:00`, `+0000`, `+00`) behave the same on
 * Safari 12 as everywhere else. A date-time without an offset is UTC (the backend's convention);
 * a bare date (`2026-10-03`, memory frontmatter) is a local calendar date.
 */
import { calendarDayDiff, formatClockTime, formatShortDate } from '@/platform';

const ISO = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d+))?)?)?\s*(Z|[+-]\d{2}(?::?\d{2})?)?$/i;

/** Epoch ms of an ISO timestamp, honouring its UTC offset; `NaN` when unreadable. */
export function parseServerTime(value: string | null | undefined): number {
  if (!value) return NaN;
  const s = value.trim();
  const m = ISO.exec(s);
  if (!m) return Date.parse(s);
  const year = Number(m[1]);
  const month = Number(m[2]) - 1;
  const day = Number(m[3]);
  if (m[4] === undefined) {
    // A bare date is a calendar day (local midnight), unless it carries an explicit zone.
    if (!m[8]) return new Date(year, month, day).getTime();
  }
  const frac = m[7] ? Math.floor(Number(`0.${m[7]}`) * 1000) : 0;
  let ms = Date.UTC(year, month, day, Number(m[4] ?? 0), Number(m[5] ?? 0), Number(m[6] ?? 0), frac);
  const tz = m[8];
  if (tz && tz.toUpperCase() !== 'Z') {
    const sign = tz.charAt(0) === '-' ? -1 : 1;
    const digits = tz.slice(1).replace(':', '');
    const minutes = Number(digits.slice(0, 2)) * 60 + Number(digits.slice(2, 4) || '0');
    ms -= sign * minutes * 60_000;
  }
  return ms;
}

export type HistoryGroup = 'Today' | 'Yesterday' | 'Previous 7 days' | 'Earlier';

export const HISTORY_GROUPS: readonly HistoryGroup[] = ['Today', 'Yesterday', 'Previous 7 days', 'Earlier'];

/** Local calendar group of an instant (unreadable times fall into "Earlier"). */
export function historyGroupOf(ts: number, now: number): HistoryGroup {
  if (!isFinite(ts)) return 'Earlier';
  const d = calendarDayDiff(ts, now);
  if (d <= 0) return 'Today';
  if (d === 1) return 'Yesterday';
  if (d < 7) return 'Previous 7 days';
  return 'Earlier';
}

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/** Trailing stamp of a history row (mockups): "14:20" today, "Thu" this week, "12 Mar" before. */
export function historyStamp(ts: number, now: number): string {
  if (!isFinite(ts)) return '';
  const d = calendarDayDiff(ts, now);
  if (d <= 0) return formatClockTime(ts);
  if (d < 7) return WEEKDAYS[new Date(ts).getDay()] ?? '';
  return formatShortDate(ts, now);
}

/** Short age for list metadata: "now", "5m", "3h", "2d", "3w", then a short date. */
export function shortAge(ts: number, now: number): string {
  if (!isFinite(ts)) return '';
  const diff = Math.max(0, now - ts);
  const min = Math.floor(diff / 60_000);
  if (min < 1) return 'now';
  if (min < 60) return `${min}m`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h}h`;
  const days = Math.floor(h / 24);
  if (days < 7) return `${days}d`;
  if (days < 56) return `${Math.floor(days / 7)}w`;
  return formatShortDate(ts, now);
}
