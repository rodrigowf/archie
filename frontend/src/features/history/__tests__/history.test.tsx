/**
 * W-14 DoD (history): offset-aware times (A-8.4), search + date groups with a fixed clock, Open now,
 * row actions (rename / duplicate / delete) against the services' fake fetch.
 */
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionInfo } from '@/services';
import { setCatalogError, setCatalogItems, snackbarStore } from '@/stores';
import { expectNoAxeViolations } from '@/test/axe';
import { jsonResponse, setupServices, teardownServices, type Harness } from '../../../services/__tests__/fakes';
import { groupSessions, historyGroupOf, historyStamp, HistoryPane, matchesQuery, parseServerTime, shortAge, type OpenNowItem } from '..';

// Rio de Janeiro: UTC−3 all year (no DST since 2019). Times below are written in several offsets.
const ORIGINAL_TZ = process.env.TZ;
beforeAll(() => {
  process.env.TZ = 'America/Sao_Paulo';
});
afterAll(() => {
  process.env.TZ = ORIGINAL_TZ;
});

/** 2026-10-04 10:00 in Rio = 13:00 UTC. */
const NOW = Date.UTC(2026, 9, 4, 13, 0, 0);

function session(id: string, title: string, last: string, extra: Partial<SessionInfo> = {}): SessionInfo {
  return { session_id: id, started_at: last, last_activity: last, title, message_count: 4, is_orchestrator: false, provider: 'claude', local_id: null, ...extra };
}

describe('parseServerTime (A-8.4: parse with the UTC offset)', () => {
  it('reads the backend shape with microseconds and an offset', () => {
    expect(parseServerTime('2026-08-28T21:49:03.550000+00:00')).toBe(Date.UTC(2026, 7, 28, 21, 49, 3, 550));
    expect(parseServerTime('2026-08-28T18:49:03.550-03:00')).toBe(Date.UTC(2026, 7, 28, 21, 49, 3, 550));
    expect(parseServerTime('2026-08-28T21:49:03Z')).toBe(Date.UTC(2026, 7, 28, 21, 49, 3));
    expect(parseServerTime('2026-08-29T03:19:03+0530')).toBe(Date.UTC(2026, 7, 28, 21, 49, 3));
  });
  it('treats a naive date-time as UTC and a bare date as a local calendar day', () => {
    expect(parseServerTime('2026-08-28T21:49:03')).toBe(Date.UTC(2026, 7, 28, 21, 49, 3));
    expect(parseServerTime('2026-10-03')).toBe(new Date(2026, 9, 3).getTime());
    expect(parseServerTime('nonsense')).toBeNaN();
    expect(parseServerTime(null)).toBeNaN();
  });
});

describe('date groups (fixed clock, local time)', () => {
  it('groups by the local calendar day, not the UTC day', () => {
    // 01:30 UTC on Oct 4 is still Oct 3 (22:30) in Rio → Yesterday, although the UTC date is today.
    expect(historyGroupOf(parseServerTime('2026-10-04T01:30:00+00:00'), NOW)).toBe('Yesterday');
    // 03:30 UTC on Oct 4 is 00:30 in Rio → Today.
    expect(historyGroupOf(parseServerTime('2026-10-04T03:30:00+00:00'), NOW)).toBe('Today');
    expect(historyGroupOf(parseServerTime('2026-09-29T12:00:00+00:00'), NOW)).toBe('Previous 7 days');
    expect(historyGroupOf(parseServerTime('2026-09-20T12:00:00+00:00'), NOW)).toBe('Earlier');
    expect(historyGroupOf(NaN, NOW)).toBe('Earlier');
  });
  it('stamps: clock time today, weekday this week, short date before', () => {
    expect(historyStamp(parseServerTime('2026-10-04T12:20:00+00:00'), NOW)).toBe('09:20');
    expect(historyStamp(parseServerTime('2026-10-01T12:00:00+00:00'), NOW)).toBe('Thu');
    expect(historyStamp(parseServerTime('2026-09-01T12:00:00+00:00'), NOW)).toMatch(/Sep/);
    expect(shortAge(NOW - 2 * 3600_000, NOW)).toBe('2h');
    expect(shortAge(NOW - 3 * 86_400_000, NOW)).toBe('3d');
  });
  it('filters by title words, sorts newest first, drops empty groups and open sessions', () => {
    const items = [
      session('a', 'Weather script', '2026-09-20T10:05:00+00:00'),
      session('b', 'Refactor the utils module', '2026-10-04T12:00:00+00:00'),
      session('c', 'Morning briefing', '2026-10-04T10:00:00+00:00', { is_orchestrator: true }),
      session('d', 'Plan the vegetable garden', '2026-10-03T19:45:00+00:00'),
    ];
    const all = groupSessions(items, { now: NOW });
    expect(all.map((g) => [g.group, g.items.map((s) => s.session_id)])).toEqual([
      ['Today', ['b', 'c']],
      ['Yesterday', ['d']],
      ['Earlier', ['a']],
    ]);
    expect(groupSessions(items, { now: NOW, query: 'the PLAN' }).map((g) => g.items.map((s) => s.session_id))).toEqual([['d']]);
    expect(groupSessions(items, { now: NOW, exclude: (s) => s.session_id === 'b' })[0]?.items.map((s) => s.session_id)).toEqual(['c']);
    expect(matchesQuery('New conversation', '')).toBe(true);
  });
});

describe('<HistoryPane>', () => {
  let h: Harness;
  const items = [
    session('mock-sess-refactor', 'Refactor the utils module', '2026-10-04T12:00:00+00:00'),
    session('mock-orch-morning', 'Orchestrator', '2026-10-03T13:00:00+00:00', { is_orchestrator: true }),
    session('mock-sess-weather', 'Weather script', '2026-09-20T10:05:00+00:00', { provider: 'gemini' }),
    session('mock-open', 'Already open', '2026-10-04T12:30:00+00:00', { local_id: 'L1' }),
  ];
  const openNow: OpenNowItem[] = [
    { id: 'L1', title: 'Already open', sdkId: 'mock-open', isArchie: false, providerLabel: 'Qwen', statusLabel: 'Using Bash…', leading: <span>T</span>, status: <span>spin</span> },
  ];

  beforeEach(() => {
    h = setupServices();
    setCatalogItems('sessions', items);
  });
  afterEach(() => {
    teardownServices();
  });

  it('shows Open now, then Today / Yesterday / Earlier, and opens rows through the shell', async () => {
    const onOpen = vi.fn();
    const onFocus = vi.fn();
    const { container } = render(<HistoryPane openNow={openNow} activeId="L1" now={NOW} onOpenSession={onOpen} onFocusOpen={onFocus} />);
    const groups = screen.getAllByRole('group').map((g) => g.getAttribute('aria-label'));
    expect(groups).toEqual(['Open now', 'Today', 'Yesterday', 'Earlier']);
    // the open session is not repeated in the date groups
    expect(within(screen.getByRole('group', { name: 'Today' })).queryByText('Already open')).toBeNull();
    expect(within(screen.getByRole('group', { name: 'Open now' })).getByText('Qwen')).toBeTruthy();
    // IA §1: a generic Archie title reads "New conversation"
    expect(within(screen.getByRole('group', { name: 'Yesterday' })).getByText('New conversation')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Weather script/ }));
    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ session_id: 'mock-sess-weather' }));
    fireEvent.click(screen.getByRole('button', { name: /Already open/ }));
    expect(onFocus).toHaveBeenCalledWith('L1');
    await expectNoAxeViolations(container);
  });

  it('search filters titles (open and past) and says when nothing matches', () => {
    render(<HistoryPane openNow={openNow} now={NOW} />);
    const field = screen.getByRole('searchbox', { name: 'Search conversations' });
    fireEvent.change(field, { target: { value: 'weather' } });
    expect(screen.queryByText('Refactor the utils module')).toBeNull();
    expect(screen.queryByText('Already open')).toBeNull();
    expect(screen.getByText('Weather script')).toBeTruthy();
    fireEvent.change(field, { target: { value: 'zzz' } });
    expect(screen.getByText(/No conversations match/)).toBeTruthy();
  });

  it('rename → PATCH with the new title (optimistic), duplicate → POST, delete asks first then DELETE', async () => {
    // a tiny server: renames stick, so the refresh after the PATCH keeps the new title
    let server = items.slice();
    h.fetch.on('GET', '/api/sessions', () => jsonResponse(server));
    h.fetch.on('PATCH', /\/rename$/, (req) => {
      const t = (req.body as { title: string }).title;
      server = server.map((x) => (req.path.indexOf(x.session_id) >= 0 ? { ...x, title: t } : x));
      return jsonResponse(undefined, 204);
    });
    h.fetch.on('POST', '/api/sessions/mock-sess-weather/duplicate', { session_id: 'copy-1' });
    h.fetch.on('DELETE', '/api/sessions/mock-sess-weather', () => jsonResponse(undefined, 204));
    render(<HistoryPane now={NOW} />);
    const row = (): HTMLElement => document.querySelector('[data-row="mock-sess-weather"]') as HTMLElement;

    // rename (the ⋮ menu path: keyboard and touch)
    fireEvent.click(within(row()).getByRole('button', { name: 'More actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Rename' }));
    const input = await screen.findByRole('textbox', { name: 'Title' });
    fireEvent.change(input, { target: { value: 'Weather script v2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => {
      expect(h.fetch.calls('PATCH', '/api/sessions/mock-sess-weather/rename')[0]?.body).toEqual({ title: 'Weather script v2' });
    });
    expect(screen.getByText('Weather script v2')).toBeTruthy();

    // duplicate (hover icon)
    fireEvent.click(within(row()).getByRole('button', { name: 'Duplicate' }));
    await waitFor(() => {
      expect(h.fetch.calls('POST', '/api/sessions/mock-sess-weather/duplicate')).toHaveLength(1);
    });
    await waitFor(() => {
      expect(snackbarStore.getState().queue.map((s) => s.message)).toContain('Duplicated “Weather script v2”');
    });

    // delete: nothing is sent before the confirmation
    fireEvent.click(within(row()).getByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog.textContent).toMatch(/trash/);
    expect(h.fetch.calls('DELETE', '/api/sessions/')).toHaveLength(0);
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
      await Promise.resolve();
    });
    await waitFor(() => {
      expect(h.fetch.calls('DELETE', '/api/sessions/mock-sess-weather')).toHaveLength(1);
    });
  });

  it('keeps the rows and shows the server error verbatim when a refresh fails', () => {
    render(<HistoryPane now={NOW} />);
    act(() => {
      setCatalogError('sessions', 'Session store is locked');
    });
    expect(screen.getByText('Weather script')).toBeTruthy();
    expect(screen.getByRole('alert').textContent).toBe('Session store is locked');
  });
});
