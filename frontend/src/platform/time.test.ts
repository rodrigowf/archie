import { describe, expect, it } from 'vitest';
import { calendarDayDiff, formatClockTime, formatRelativeTime, formatShortDate } from './time';

const now = new Date(2026, 9, 3, 15, 0, 0).getTime(); // 3 Oct 2026, 15:00 local

describe('time', () => {
  it('formats recent times', () => {
    expect(formatRelativeTime(now - 10_000, now)).toBe('just now');
    expect(formatRelativeTime(now + 60_000, now)).toBe('just now');
    expect(formatRelativeTime(now - 60_000, now)).toBe('1 min ago');
    expect(formatRelativeTime(now - 25 * 60_000, now)).toBe('25 min ago');
    expect(formatRelativeTime(now - 3 * 3_600_000, now)).toBe('3 h ago');
  });

  it('uses calendar days for yesterday / N days', () => {
    expect(formatRelativeTime(new Date(2026, 9, 2, 23, 0).getTime(), now)).toBe('yesterday');
    expect(formatRelativeTime(new Date(2026, 8, 29, 12, 0).getTime(), now)).toBe('4 days ago');
    expect(calendarDayDiff(new Date(2026, 9, 3, 0, 1), now)).toBe(0);
    expect(calendarDayDiff(new Date(2026, 9, 2, 23, 59), now)).toBe(1);
  });

  it('falls back to a short date after a week', () => {
    const then = new Date(2026, 8, 1, 9, 0).getTime();
    expect(formatRelativeTime(then, now)).toBe(formatShortDate(then, now));
    expect(formatShortDate(new Date(2025, 2, 12).getTime(), now)).toMatch(/2025/);
    expect(formatShortDate(then, now)).not.toMatch(/2026/);
  });

  it('formats clock time with zero padding', () => {
    expect(formatClockTime(new Date(2026, 0, 1, 7, 5))).toBe('07:05');
  });
});
