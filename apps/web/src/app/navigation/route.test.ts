import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { pushOverlay, resetOverlayStackForTests } from '@/ui/a11y/overlayStack';
import { installHashSync, navigate, parseHash, resetRoute, routeStore } from './route';

function settle(): Promise<void> {
  // history.go() traverses asynchronously; popstate and hashchange are separate tasks.
  return new Promise((resolve) => setTimeout(resolve, 50));
}

describe('route ↔ overlay history', () => {
  let uninstall: () => void = () => undefined;

  beforeEach(() => {
    window.history.replaceState(null, '', location.pathname + '#/');
    resetOverlayStackForTests();
    resetRoute();
    uninstall = installHashSync();
  });
  afterEach(() => {
    uninstall();
    resetOverlayStackForTests();
  });

  it('closing a screen stays closed (silent pop must not re-open it from the stale hash)', async () => {
    // Open Settings the way the shell does: route first, then the screen registers its layer.
    navigate({ name: 'settings', page: null });
    const screen = pushOverlay({ onClose: () => navigate({ name: 'workspace' }), history: true });
    await settle();
    expect(location.hash).toBe('#/settings');

    // Close via the in-app Back arrow: route changes, then the screen unmounts.
    navigate({ name: 'workspace' });
    screen.remove();
    await settle();

    expect(routeStore.getState().route).toEqual({ name: 'workspace' });
    expect(parseHash(location.hash)).toEqual({ name: 'workspace' });
  });

  it('switching from Settings to another destination stays there', async () => {
    navigate({ name: 'settings', page: null });
    const screen = pushOverlay({ onClose: () => navigate({ name: 'workspace' }), history: true });
    await settle();

    navigate({ name: 'memory', path: null });
    screen.remove();
    await settle();

    expect(routeStore.getState().route).toEqual({ name: 'memory', path: null });
    expect(location.hash).toBe('#/memory');
  });

  it('browser Back closes the screen and lands on the workspace', async () => {
    navigate({ name: 'settings', page: null });
    const screen = pushOverlay({
      onClose: () => {
        navigate({ name: 'workspace' });
        screen.remove();
      },
      history: true,
    });
    await settle();

    window.history.back();
    await settle();

    expect(routeStore.getState().route).toEqual({ name: 'workspace' });
    expect(parseHash(location.hash)).toEqual({ name: 'workspace' });
  });

  it('a typed hash still navigates', async () => {
    window.location.hash = '#/visuals';
    await settle();
    expect(routeStore.getState().route).toEqual({ name: 'visuals', path: null });
  });
});
