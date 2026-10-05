import { describe, expect, it, vi } from 'vitest';
import { APP_HEIGHT_VAR, installViewportHeight } from './viewport';

describe('viewport height', () => {
  it('mirrors window.innerHeight into --app-height on resize and orientation change', () => {
    vi.useFakeTimers();
    const root = document.documentElement;
    const h = vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(915);
    const uninstall = installViewportHeight();
    expect(installViewportHeight()).toBe(uninstall); // idempotent
    expect(root.style.getPropertyValue(APP_HEIGHT_VAR)).toBe('915px');
    h.mockReturnValue(412);
    window.dispatchEvent(new Event('resize'));
    expect(root.style.getPropertyValue(APP_HEIGHT_VAR)).toBe('412px');
    window.dispatchEvent(new Event('orientationchange'));
    h.mockReturnValue(400); // iOS settles after the rotation animation
    vi.advanceTimersByTime(300);
    expect(root.style.getPropertyValue(APP_HEIGHT_VAR)).toBe('400px');
    uninstall();
    h.mockReturnValue(100);
    window.dispatchEvent(new Event('resize'));
    expect(root.style.getPropertyValue(APP_HEIGHT_VAR)).toBe('400px');
    vi.useRealTimers();
    h.mockRestore();
  });
});
