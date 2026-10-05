/** The orb follows the live level: equalizer bars + rings that grow with it (Rodrigo, 2026-10-05). */
import { render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { setLowEndForced } from '@/platform';
import { barScale, LevelOrb, ringScale } from '../LevelOrb';

const scaleOf = (el: Element | null | undefined): number => Number(/scale[Y]?\(([\d.]+)\)/.exec((el as SVGElement | null)?.style.transform ?? '')?.[1] ?? NaN);

afterEach(() => {
  setLowEndForced(false);
  vi.useRealTimers();
});

describe('LevelOrb', () => {
  it('bar and ring scales grow with the level and stay in range', () => {
    expect(barScale(0, 1, 0.5)).toBeCloseTo(0.3);
    expect(barScale(1, 1, 1)).toBeCloseTo(1);
    expect(barScale(0.5, 0.6, 0.5)).toBeLessThan(barScale(0.5, 1, 0.5)); // per-bar response
    expect(ringScale(0, 0.24)).toBeCloseTo(0.84);
    expect(ringScale(1, 0.24)).toBeCloseTo(1.08);
    expect(ringScale(5, 0.24)).toBeCloseTo(1.08);
  });

  it('live: loud audio grows the rings and raises the bars; silence shrinks them back', () => {
    vi.useFakeTimers();
    let rms = 0.2;
    const { container } = render(<LevelOrb tone="listen" level={() => rms} />);
    vi.advanceTimersByTime(66 * 10);
    const [o1, o2] = Array.from(container.querySelectorAll('circle'));
    const loudRing = scaleOf(o1);
    const loudBar = scaleOf(container.querySelector('rect'));
    expect(loudRing).toBeGreaterThan(1);
    expect(scaleOf(o2)).toBeGreaterThan(0.9);
    expect(loudBar).toBeGreaterThan(0.7);

    rms = 0;
    vi.advanceTimersByTime(66 * 40);
    expect(scaleOf(o1)).toBeLessThan(0.9);
    expect(scaleOf(container.querySelector('rect'))).toBeLessThan(0.4);
  });

  it('low-end / reduce motion: bars follow the level, rings keep their calm pulse', () => {
    setLowEndForced(true);
    vi.useFakeTimers();
    const { container } = render(<LevelOrb tone="speak" level={() => 0.2} />);
    vi.advanceTimersByTime(100 * 10);
    expect(Number.isNaN(scaleOf(container.querySelector('circle')))).toBe(true);
    expect(scaleOf(container.querySelector('rect'))).toBeGreaterThan(0.7);
  });
});
