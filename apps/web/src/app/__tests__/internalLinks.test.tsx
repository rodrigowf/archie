/**
 * Opening a visualization link (spec 12 §9.4): only real pages open in the viewer; anything else
 * offers the browser instead of framing a 404 (or, before, the whole app).
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setCatalogItems, snackbarStore, tabsStore } from '@/stores';
import { flushPromises, type Harness } from '../../services/__tests__/fakes';
import { setupApp, teardownApp } from './testUtils';
import { openInternalTarget } from '../internalLinks';

const viz = (path: string) => ({ path, url: `/${path}`, title: path, created: null, modified: '2026-10-09T00:00:00+00:00', size: 1 });
let h: Harness;

beforeEach(() => {
  h = setupApp(); // expanded: opens a tab
});
afterEach(() => {
  teardownApp();
});

const tabIds = () => tabsStore.getState().tabs.map((t) => t.id);

describe('openInternalTarget', () => {
  it('opens listed pages and visualizations/ at once', () => {
    setCatalogItems('visuals', [viz('dash/index.html')] as never);
    openInternalTarget({ kind: 'visual', path: 'dash/index.html' });
    openInternalTarget({ kind: 'visual', path: 'visualizations/new.html' });
    expect(tabIds()).toEqual(expect.arrayContaining(['viz:dash/index.html', 'viz:visualizations/new.html']));
  });

  it('refreshes the list for an unknown page; a page that is there opens, a missing one offers the browser', async () => {
    h.fetch.on('GET', '/api/visualizations', [viz('fresh/index.html')]);
    openInternalTarget({ kind: 'visual', path: 'fresh/index.html' });
    await flushPromises();
    expect(tabIds()).toContain('viz:fresh/index.html');

    openInternalTarget({ kind: 'visual', path: 'gone/index.html' });
    await flushPromises();
    expect(tabIds()).not.toContain('viz:gone/index.html');
    const snack = snackbarStore.getState();
    expect(JSON.stringify(snack)).toContain('Open in browser');
  });
});
