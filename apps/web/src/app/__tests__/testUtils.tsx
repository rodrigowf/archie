/**
 * W-07 test helpers: a controllable `matchMedia` (window size classes), and a fresh app state
 * (services with fake socket/fetch, stores, shell, route, overlay stack).
 */
import { act } from '@testing-library/react';
import { resetOverlayStackForTests } from '@/ui/a11y';
import { prefsStore, DEFAULT_PREFS } from '@/stores';
import { resetRoute } from '../navigation/route';
import { resetShell } from '../shell/shellState';
import { setupServices, teardownServices, type Harness } from '../../services/__tests__/fakes';

export { FakeWebSocket, flushPromises, jsonResponse } from '../../services/__tests__/fakes';

type Listener = (e: { matches: boolean }) => void;

let width = 1440;
const listeners = new Set<Listener>();
const original = window.matchMedia;

export const SIZES = { compact: 412, medium: 768, expanded: 1440 } as const;

function install(): void {
  window.matchMedia = ((query: string) => {
    const min = /min-width:\s*(\d+)px/.exec(query);
    const max = /max-width:\s*([\d.]+)px/.exec(query);
    const evaluate = (): boolean => {
      if (!min && !max) return false;
      return (!min || width >= Number(min[1])) && (!max || width <= Number(max[1]));
    };
    return {
      get matches() {
        return evaluate();
      },
      media: query,
      onchange: null,
      addListener: (fn: Listener) => listeners.add(fn),
      removeListener: (fn: Listener) => listeners.delete(fn),
      addEventListener: (_t: string, fn: Listener) => listeners.add(fn),
      removeEventListener: (_t: string, fn: Listener) => listeners.delete(fn),
      dispatchEvent: () => false,
    } as unknown as MediaQueryList;
  }) as typeof window.matchMedia;
}

/** Resize the (virtual) window; fires the media listeners. */
export function setWidth(w: number): void {
  width = w;
  act(() => {
    for (const fn of Array.from(listeners)) fn({ matches: false });
  });
}

export function setupApp(w: number = SIZES.expanded): Harness {
  width = w;
  listeners.clear();
  install();
  const h = setupServices();
  h.fetch.on('POST', /^\/api\/sessions\/[^/]+\/close$/, () => new Response(null, { status: 204 }));
  window.history.replaceState(null, '', '#/');
  resetShell();
  resetRoute();
  resetOverlayStackForTests();
  prefsStore.setState({ ...DEFAULT_PREFS }, true);
  return h;
}

export function teardownApp(): void {
  teardownServices();
  window.matchMedia = original;
  listeners.clear();
}
