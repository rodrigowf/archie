import { afterEach, describe, expect, it, vi } from 'vitest';
import * as capabilities from './capabilities';
import { copyText, copyWithTextarea } from './clipboard';

afterEach(() => {
  vi.restoreAllMocks();
});

function stubExecCommand(result: boolean) {
  const fn = vi.fn(() => result);
  Object.defineProperty(document, 'execCommand', { value: fn, configurable: true, writable: true });
  return fn;
}

describe('clipboard', () => {
  it('uses the Clipboard API when available', async () => {
    vi.spyOn(capabilities, 'getCapabilities').mockReturnValue({
      ...capabilities.detectCapabilities(),
      clipboardApi: true,
    });
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    await expect(copyText('hello')).resolves.toBe(true);
    expect(writeText).toHaveBeenCalledWith('hello');
  });

  it('falls back to textarea + execCommand on Safari 12 / plain HTTP', async () => {
    vi.spyOn(capabilities, 'getCapabilities').mockReturnValue({
      ...capabilities.detectCapabilities(),
      clipboardApi: false,
    });
    const exec = stubExecCommand(true);
    await expect(copyText('legacy')).resolves.toBe(true);
    expect(exec).toHaveBeenCalledWith('copy');
    expect(document.querySelector('textarea')).toBeNull(); // cleaned up
  });

  it('falls back when the Clipboard API rejects', async () => {
    vi.spyOn(capabilities, 'getCapabilities').mockReturnValue({
      ...capabilities.detectCapabilities(),
      clipboardApi: true,
    });
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: () => Promise.reject(new Error('NotAllowedError')) },
      configurable: true,
    });
    stubExecCommand(true);
    await expect(copyText('x')).resolves.toBe(true);
  });

  it('reports failure and restores focus', () => {
    const button = document.createElement('button');
    document.body.appendChild(button);
    button.focus();
    stubExecCommand(false);
    expect(copyWithTextarea('x')).toBe(false);
    expect(document.activeElement).toBe(button);
    button.remove();
  });
});
