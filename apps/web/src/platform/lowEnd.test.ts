import { describe, expect, it } from 'vitest';
import { LOW_END_CLASS, detectLowEndDevice, initLowEnd, isLowEnd, setLowEndForced } from './lowEnd';

describe('low-end mode (LOAD-BEARING inv02 §1.2)', () => {
  it('detects <= 2 cores or <= 1 GB', () => {
    expect(detectLowEndDevice({ hardwareConcurrency: 2 })).toBe(true); // iPad mini 2
    expect(detectLowEndDevice({ hardwareConcurrency: 8, deviceMemory: 1 })).toBe(true);
    expect(detectLowEndDevice({ hardwareConcurrency: 8, deviceMemory: 0.5 })).toBe(true);
    expect(detectLowEndDevice({ hardwareConcurrency: 4, deviceMemory: 4 })).toBe(false);
    expect(detectLowEndDevice({})).toBe(false);
  });

  it('sets html.low-end on a low-end device, not on a fast one', () => {
    const root = document.createElement('html');
    expect(initLowEnd({ target: 'main', nav: { hardwareConcurrency: 2 }, root, forced: false })).toBe(true);
    expect(root.classList.contains(LOW_END_CLASS)).toBe(true);
    expect(initLowEnd({ target: 'main', nav: { hardwareConcurrency: 8 }, root, forced: false })).toBe(false);
    expect(root.classList.contains(LOW_END_CLASS)).toBe(false);
  });

  it('is on by default for the compat build', () => {
    const root = document.createElement('html');
    expect(initLowEnd({ target: 'compat', nav: { hardwareConcurrency: 8 }, root, forced: false })).toBe(true);
    expect(root.classList.contains(LOW_END_CLASS)).toBe(true);
  });

  it('the Reduce motion pref forces it on and off', () => {
    const root = document.createElement('html');
    initLowEnd({ target: 'main', nav: { hardwareConcurrency: 8 }, root, forced: false });
    setLowEndForced(true);
    expect(isLowEnd()).toBe(true);
    expect(root.classList.contains(LOW_END_CLASS)).toBe(true);
    setLowEndForced(false);
    expect(isLowEnd()).toBe(false);
    expect(root.classList.contains(LOW_END_CLASS)).toBe(false);
  });
});
