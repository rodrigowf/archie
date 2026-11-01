/**
 * W-14 DoD (visuals): exact iframe sandbox tokens (F-36, VZ-3), Reload = remount, the iframe stays
 * mounted while hidden, segment-encoded URLs (VZ-1), "Show on TV" hidden unless the BX-2 probe
 * says available and posting the right path (mocked; never the real endpoint), list rename.
 */
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { probeCast, type VisualizationInfo } from '@/services';
import { bumpContent, capabilitiesStore, patchCapabilities, resetContentChanges, setCatalogItems, snackbarStore } from '@/stores';
import { expectNoAxeViolations } from '@/test/axe';
import { jsonResponse, setupServices, teardownServices, type Harness } from '../../../services/__tests__/fakes';
import { partitionVisuals, VisualCard, VisualsPane, vizFolder, vizHref } from '..';
import { LIVE_RELOAD_DEBOUNCE_MS, UPDATED_CUE_MS, VisualViewer, VIZ_SANDBOX } from '../VisualViewer';

const NOW = Date.UTC(2026, 9, 4, 13, 0, 0);
const VIZ: VisualizationInfo[] = [
  { path: 'charts/weekly energy#1.html', url: '/charts/weekly energy#1.html', title: 'Weekly energy usage', created: '2026-10-04T10:00:00+00:00', modified: '2026-10-04T11:00:00+00:00', size: 1200, favorite: false },
  { path: 'solar-system/index.html', url: '/solar-system/index.html', title: 'Solar system', created: '2026-09-01T10:00:00+00:00', modified: '2026-09-20T10:00:00+00:00', size: 900, favorite: false },
  { path: 'root.html', url: '/root.html', title: 'Root page', created: '2026-09-01T10:00:00+00:00', modified: '2026-09-01T10:00:00+00:00', size: 10, favorite: false },
];

const W = VIZ[0] as VisualizationInfo;

let h: Harness;
beforeEach(() => {
  h = setupServices();
  h.fetch.on('GET', '/api/visualizations', VIZ);
  setCatalogItems('visuals', VIZ);
  patchCapabilities({ castAvailable: false, castReason: null });
});
afterEach(() => {
  teardownServices();
  capabilitiesStore.setState({ castAvailable: false, castReason: null });
});

describe('helpers', () => {
  it('encodes each URL segment (VZ-1) and names the folder (VZ-5)', () => {
    expect(vizHref('charts/weekly energy#1.html', '/charts/weekly energy#1.html')).toBe('http://backend.test/charts/weekly%20energy%231.html');
    expect(vizHref('a/b.html')).toBe('http://backend.test/a/b.html');
    expect(vizFolder('charts/weekly.html')).toBe('charts');
    expect(vizFolder('root.html')).toBe('public');
  });

  it('splits starred from the rest, each in server order, no duplicates (VZ-8)', () => {
    const items = VIZ.map((v, i) => ({ ...v, favorite: i !== 1 }));
    const { starred, rest } = partitionVisuals(items);
    expect(starred.map((v) => v.path)).toEqual(['charts/weekly energy#1.html', 'root.html']);
    expect(rest.map((v) => v.path)).toEqual(['solar-system/index.html']);
    expect(partitionVisuals(VIZ).starred).toEqual([]);
  });
});

describe('<VisualViewer> live reload (spec 12 §9.3, VZ-6)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    resetContentChanges();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('remounts once per burst of changes, shows the Updated cue, and ignores older and other changes', () => {
    bumpContent('visuals', [{ path: W.path, deleted: false }]); // before mount: already in what it loads
    const { container } = render(<VisualViewer path={W.path} url={W.url} hidden={false} />);
    const first = container.querySelector('iframe');
    act(() => {
      vi.advanceTimersByTime(LIVE_RELOAD_DEBOUNCE_MS * 2);
    });
    expect(container.querySelector('iframe')).toBe(first);

    act(() => {
      bumpContent('visuals', [{ path: 'root.html', deleted: false }]);
    });
    act(() => {
      vi.advanceTimersByTime(LIVE_RELOAD_DEBOUNCE_MS * 2);
    });
    expect(container.querySelector('iframe')).toBe(first);

    act(() => {
      bumpContent('visuals', [{ path: W.path, deleted: false }]);
    });
    act(() => {
      vi.advanceTimersByTime(LIVE_RELOAD_DEBOUNCE_MS / 2);
    });
    act(() => {
      bumpContent('visuals', [{ path: W.path, deleted: false }]);
    });
    act(() => {
      vi.advanceTimersByTime(LIVE_RELOAD_DEBOUNCE_MS / 2 + 10);
    });
    expect(container.querySelector('iframe')).toBe(first); // the second change restarted the wait
    act(() => {
      vi.advanceTimersByTime(LIVE_RELOAD_DEBOUNCE_MS);
    });
    const second = container.querySelector('iframe') as HTMLIFrameElement;
    expect(second).not.toBe(first);
    expect(second.getAttribute('data-reload')).toBe('1');
    expect(screen.getByRole('status').textContent).toBe('Updated');
    act(() => {
      vi.advanceTimersByTime(UPDATED_CUE_MS + 10);
    });
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('a hidden view waits until it is shown again', () => {
    const { container, rerender } = render(<VisualViewer path={W.path} url={W.url} hidden />);
    const first = container.querySelector('iframe');
    act(() => {
      bumpContent('visuals', [{ path: W.path, deleted: false }]);
    });
    act(() => {
      vi.advanceTimersByTime(LIVE_RELOAD_DEBOUNCE_MS * 3);
    });
    expect(container.querySelector('iframe')).toBe(first);
    rerender(<VisualViewer path={W.path} url={W.url} hidden={false} />);
    act(() => {
      vi.advanceTimersByTime(LIVE_RELOAD_DEBOUNCE_MS + 10);
    });
    expect(container.querySelector('iframe')).not.toBe(first);
  });

  it('a deleted page is not reloaded and says so', () => {
    const { container } = render(<VisualViewer path={W.path} url={W.url} hidden={false} />);
    const first = container.querySelector('iframe');
    act(() => {
      bumpContent('visuals', [{ path: W.path, deleted: true }]);
    });
    act(() => {
      vi.advanceTimersByTime(LIVE_RELOAD_DEBOUNCE_MS * 2);
    });
    expect(container.querySelector('iframe')).toBe(first);
    expect(screen.getByText(/^Deleted · charts/)).toBeTruthy();
  });
});

describe('<VisualViewer>', () => {
  it('uses the exact sandbox tokens; Reload remounts the iframe; hidden keeps it mounted', async () => {
    const { container, rerender } = render(<VisualViewer path={W.path} url={W.url} hidden={false} />);
    const frame = container.querySelector('iframe') as HTMLIFrameElement;
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts allow-same-origin allow-popups allow-forms allow-modals');
    expect(VIZ_SANDBOX).toBe(frame.getAttribute('sandbox'));
    expect(frame.getAttribute('src')).toBe('http://backend.test/charts/weekly%20energy%231.html');
    expect(frame.getAttribute('title')).toBe('Weekly energy usage');

    // hidden: same node (state of an interactive visualization survives tab switches)
    rerender(<VisualViewer path={W.path} url={W.url} hidden />);
    expect(container.querySelector('iframe')).toBe(frame);
    rerender(<VisualViewer path={W.path} url={W.url} hidden={false} />);
    expect(container.querySelector('iframe')).toBe(frame);

    // ⋮ Reload → a new iframe element (remount), same src and sandbox
    fireEvent.click(screen.getByRole('button', { name: 'Visual menu' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Reload' }));
    const next = container.querySelector('iframe') as HTMLIFrameElement;
    expect(next).not.toBe(frame);
    expect(next.getAttribute('sandbox')).toBe(VIZ_SANDBOX);
    expect(next.getAttribute('src')).toBe(frame.getAttribute('src'));
    fireEvent.click(screen.getByRole('button', { name: 'Visual menu' }));
    expect((await screen.findAllByRole('menuitem')).map((m) => m.textContent)).toEqual(['Reload', 'Open in browser', 'Copy link']);
  });

  it('falls back to the list entry, then /<path>, when the tab has no url', () => {
    const { container } = render(<VisualViewer path="solar-system/index.html" url={undefined} hidden={false} />);
    expect(container.querySelector('iframe')?.getAttribute('src')).toBe('http://backend.test/solar-system/index.html');
  });

  it('hides "Show on TV" when the probe fails (404, SPA fallback, unavailable)', async () => {
    h.fetch.on('GET', '/api/visualizations/cast', () => new Response('<!doctype html><div id=root>', { status: 200, headers: { 'content-type': 'text/html' } }));
    await probeCast();
    expect(capabilitiesStore.getState().castAvailable).toBe(false);
    const { container } = render(<VisualViewer path={W.path} url={W.url} hidden={false} />);
    expect(screen.queryByRole('button', { name: /Show on TV/ })).toBeNull();
    // axe on the toolbar (axe cannot enter a cross-document iframe in jsdom)
    await expectNoAxeViolations(container.querySelector('section > div') as Element);
  });

  it('shows it when the probe says available and posts the visual path (mocked endpoint)', async () => {
    h.fetch.on('GET', '/api/visualizations/cast', { available: true, reason: null });
    h.fetch.on('POST', '/api/visualizations/cast', { ok: true, message: 'Showing on TV: https://192.168.0.200/charts/weekly%20energy%231.html' });
    await act(async () => {
      await probeCast();
    });
    render(<VisualViewer path={W.path} url={W.url} hidden={false} />);
    fireEvent.click(screen.getByRole('button', { name: /Show on TV/ }));
    await waitFor(() => {
      expect(h.fetch.calls('POST', '/api/visualizations/cast')).toHaveLength(1);
    });
    expect(h.fetch.calls('POST', '/api/visualizations/cast')[0]?.body).toEqual({ path: 'charts/weekly energy#1.html' });
    await waitFor(() => {
      expect(snackbarStore.getState().queue.map((s) => s.message)).toContain('Showing “Weekly energy usage” on TV');
    });
  });

  it('a failed cast shows the server message verbatim', async () => {
    patchCapabilities({ castAvailable: true });
    h.fetch.on('POST', '/api/visualizations/cast', { ok: false, message: 'No Fire TV connected over adb' });
    render(<VisualViewer path={W.path} url={W.url} hidden={false} />);
    fireEvent.click(screen.getByRole('button', { name: /Show on TV/ }));
    await waitFor(() => {
      expect(snackbarStore.getState().queue.map((s) => s.message)).toContain('No Fire TV connected over adb');
    });
  });
});

describe('<VisualsPane>', () => {
  it('refreshes on open, lists title + folder + age, opens, filters and renames', async () => {
    h.fetch.on('PATCH', '/api/visualizations/rename', () => jsonResponse(undefined, 204));
    const onOpen = vi.fn();
    const { container } = render(<VisualsPane onOpen={onOpen} now={NOW} />);
    expect(h.fetch.calls('GET', '/api/visualizations').length).toBeGreaterThan(0);
    const row = (): HTMLElement => container.querySelector('[data-row="solar-system/index.html"]') as HTMLElement;
    expect(row().textContent).toContain('Solar system');
    expect(container.querySelector('[data-row="charts/weekly energy#1.html"]')?.textContent).toContain('charts · 2h');
    fireEvent.click(within(row()).getByRole('button', { name: /Solar system/ }));
    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ path: 'solar-system/index.html' }));
    // no "Show on TV" without the probe
    expect(within(row()).queryByRole('button', { name: 'Show on TV' })).toBeNull();

    fireEvent.change(screen.getByRole('searchbox', { name: 'Search visuals' }), { target: { value: 'weekly' } });
    expect(row()).toBeNull();
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search visuals' }), { target: { value: '' } });

    fireEvent.click(within(row()).getByRole('button', { name: 'Rename' }));
    const input = await screen.findByRole('textbox', { name: 'Title' });
    fireEvent.change(input, { target: { value: 'Planets' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => {
      expect(h.fetch.calls('PATCH', '/api/visualizations/rename')[0]?.body).toEqual({ path: 'solar-system/index.html', title: 'Planets' });
    });
    await expectNoAxeViolations(container);
  });
});

describe('<VisualsPane> favorites (VZ-8)', () => {
  const starredViz = (paths: string[]): VisualizationInfo[] => VIZ.map((v) => ({ ...v, favorite: paths.includes(v.path) }));
  const lists = (c: HTMLElement) => Array.from(c.querySelectorAll('[role="list"]')).map((l) => [l.getAttribute('aria-label'), Array.from(l.querySelectorAll('[data-row]')).map((r) => r.getAttribute('data-row'))]);

  it('nothing starred: one list, no headers', () => {
    const { container } = render(<VisualsPane now={NOW} />);
    expect(lists(container)).toEqual([['Visuals', VIZ.map((v) => v.path)]]);
    expect(screen.queryByText('Starred')).toBeNull();
    expect(screen.queryByText('All visuals')).toBeNull();
  });

  it('Starred on top, the rest below, search filters both and hides an empty section', async () => {
    const items = starredViz(['root.html']);
    h.fetch.on('GET', '/api/visualizations', items);
    setCatalogItems('visuals', items);
    const { container } = render(<VisualsPane now={NOW} />);
    expect(lists(container)).toEqual([
      ['Starred', ['root.html']],
      ['All visuals', ['charts/weekly energy#1.html', 'solar-system/index.html']],
    ]);
    expect(screen.getByText('Starred')).toBeTruthy();
    expect(screen.getByText('All visuals')).toBeTruthy();
    // the set star is a pressed toggle; an unset one is a plain "Star" toggle
    const root = container.querySelector('[data-row="root.html"]') as HTMLElement;
    expect(within(root).getByRole('button', { name: 'Unstar' }).getAttribute('aria-pressed')).toBe('true');
    const solar = container.querySelector('[data-row="solar-system/index.html"]') as HTMLElement;
    expect(within(solar).getByRole('button', { name: 'Star' }).getAttribute('aria-pressed')).toBe('false');
    await expectNoAxeViolations(container);

    const search = screen.getByRole('searchbox', { name: 'Search visuals' });
    fireEvent.change(search, { target: { value: 'solar' } });
    expect(lists(container)).toEqual([['Visuals', ['solar-system/index.html']]]);
    expect(screen.queryByText('Starred')).toBeNull();
    fireEvent.change(search, { target: { value: 'root' } });
    expect(lists(container)).toEqual([['Starred', ['root.html']]]);
    expect(screen.queryByText('All visuals')).toBeNull();
  });

  it('the row toggle stars optimistically and reverts on a server error', async () => {
    h.fetch.on('PUT', '/api/visualizations/favorite', () => jsonResponse(undefined, 204));
    const { container } = render(<VisualsPane now={NOW} />);
    const row = (p: string) => container.querySelector(`[data-row="${p}"]`) as HTMLElement;
    fireEvent.click(within(row('solar-system/index.html')).getByRole('button', { name: 'Star' }));
    expect(lists(container)[0]).toEqual(['Starred', ['solar-system/index.html']]);
    await waitFor(() => {
      expect(h.fetch.calls('PUT', '/api/visualizations/favorite')[0]?.body).toEqual({ path: 'solar-system/index.html', favorite: true });
    });

    h.fetch.on('PUT', '/api/visualizations/favorite', () => jsonResponse({ detail: 'Unknown visualization' }, 404));
    fireEvent.click(within(row('root.html')).getByRole('button', { name: 'Star' }));
    expect(within(row('root.html')).getByRole('button', { name: 'Unstar' })).toBeTruthy();
    await waitFor(() => {
      expect(within(row('root.html')).getByRole('button', { name: 'Star' })).toBeTruthy();
    });
    expect(snackbarStore.getState().queue.map((q) => q.message)).toContain('Unknown visualization');
  });
});

describe('<VisualViewer> star', () => {
  it('toggles the open visual; hidden while it is not in the list', async () => {
    resetContentChanges(); // an earlier test marked W deleted
    h.fetch.on('PUT', '/api/visualizations/favorite', () => jsonResponse(undefined, 204));
    render(<VisualViewer path={W.path} url={W.url} hidden={false} />);
    const star = screen.getByRole('button', { name: 'Star' });
    expect(star.getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(star);
    expect(screen.getByRole('button', { name: 'Unstar' }).getAttribute('aria-pressed')).toBe('true');
    await waitFor(() => {
      expect(h.fetch.calls('PUT', '/api/visualizations/favorite')[0]?.body).toEqual({ path: W.path, favorite: true });
    });
    const { unmount } = render(<VisualViewer path="gone.html" url={undefined} hidden={false} />);
    expect(screen.getAllByRole('button', { name: /^(Star|Unstar)$/ })).toHaveLength(1);
    unmount();
  });
});

describe('<VisualCard>', () => {
  it('Open always; Show on TV only when available', () => {
    const onOpen = vi.fn();
    const { rerender } = render(<VisualCard path="charts/w.html" title="Weekly energy usage" modified="2026-10-04T11:00:00+00:00" onOpen={onOpen} />);
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    expect(onOpen).toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: /Show on TV/ })).toBeNull();
    act(() => {
      patchCapabilities({ castAvailable: true });
    });
    rerender(<VisualCard path="charts/w.html" title="Weekly energy usage" onOpen={onOpen} />);
    expect(screen.getByRole('button', { name: /Show on TV/ })).toBeTruthy();
  });
});
