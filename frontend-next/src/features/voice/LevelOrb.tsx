/**
 * The level orb (mockups `.orb`): three rings and four level bars. While live, the bars are a small
 * equalizer on the RMS (mic while listening, speaker while Archie speaks; each bar answers a little
 * differently, like the legacy `VolumeBars`) and the two outer rings grow with the overall level
 * instead of pulsing. Polled at ~15 fps (10 fps on low-end, spec 13 §2.7, where the rings keep their
 * calm pulse) and written straight to the DOM, so audio levels never re-render React.
 *
 * Tones: `listen` (primary), `speak` (tertiary), `idle` (outline, still), `recon` (warning,
 * dimmed, slow pulse, flat bars; P-2).
 */
import { useEffect, useRef } from 'react';
import { isLowEnd } from '@/platform';
import { cx } from '@/ui/primitives';
import { visualLevel } from '@/voice';
import styles from './VoiceDock.module.css';

export type OrbTone = 'listen' | 'speak' | 'idle' | 'recon';

/** Bar geometry from the mockups (x, y, height on a 100 × 100 box). */
const BARS: readonly { x: number; y: number; h: number; f: number }[] = [
  { x: 34.5, y: 43, h: 14, f: 0.7 },
  { x: 43, y: 36, h: 28, f: 1 },
  { x: 51.5, y: 39, h: 22, f: 0.85 },
  { x: 60, y: 44, h: 12, f: 0.6 },
];

const TONE_CLASS: Record<OrbTone, string | undefined> = {
  listen: undefined,
  speak: styles.orbSpeak,
  idle: styles.orbIdle,
  recon: styles.orbRecon,
};

export interface LevelOrbProps {
  readonly tone: OrbTone;
  readonly size?: number;
  /** RMS source; omit for the decorative (CSS-animated) orb. */
  readonly level?: () => number;
  /** No pulse (Active elsewhere, errors). */
  readonly still?: boolean;
  readonly className?: string;
}

/** Equalizer bar scale for a smoothed level (0..1): per-bar response [f] plus a little shimmer. */
export function barScale(level: number, f: number, shimmer: number): number {
  const v = Math.min(1, level * (0.6 + f * 0.6) * (0.85 + 0.3 * shimmer));
  return 0.3 + 0.7 * v;
}

/** Outer ring scale for a smoothed level: the resting pulse's low point (0.84) up to just past the orb. */
export function ringScale(level: number, reach: number): number {
  return 0.84 + reach * Math.min(1, level);
}

export function LevelOrb({ tone, size = 60, level, still = false, className }: LevelOrbProps) {
  const bars = useRef<(SVGRectElement | null)[]>([]);
  const rings = useRef<(SVGCircleElement | null)[]>([]);
  const live = !!level && tone !== 'recon' && tone !== 'idle';
  const calm = live && isLowEnd();

  useEffect(() => {
    if (!live || !level) return undefined;
    const lowEnd = isLowEnd();
    const period = lowEnd ? 100 : 66;
    let smooth = 0;
    let ring = 0;
    const els = bars.current;
    const halos = rings.current;
    const t = setInterval(() => {
      const v = visualLevel(level());
      smooth = v > smooth ? v : smooth * 0.7 + v * 0.3; // fast attack, slow release
      ring = v > ring ? ring * 0.5 + v * 0.5 : ring * 0.88 + v * 0.12; // the rings breathe slower
      els.forEach((el, i) => {
        const b = BARS[i] as (typeof BARS)[number];
        // a CSS transform: the CSS transform-origin (50px 50px, the bars' common centre) applies
        if (el) el.style.transform = `scaleY(${barScale(smooth, b.f, Math.random()).toFixed(3)})`;
      });
      if (!lowEnd) {
        halos.forEach((el, i) => {
          if (el) el.style.transform = `scale(${ringScale(ring, i === 0 ? 0.24 : 0.16).toFixed(3)})`;
        });
      }
    }, period);
    return () => {
      clearInterval(t);
      els.forEach((el) => {
        if (el) el.style.transform = '';
      });
      halos.forEach((el) => {
        if (el) el.style.transform = '';
      });
    };
  }, [live, level]);

  return (
    <svg
      viewBox="0 0 100 100"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
      className={cx(
        styles.orb,
        TONE_CLASS[tone],
        (still || tone === 'idle') && styles.orbStill,
        live && styles.orbLive,
        live && !calm && styles.orbLiveRings,
        className,
      )}
    >
      <circle
        ref={(el) => {
          rings.current[0] = el;
        }}
        className={styles.o1}
        cx="50"
        cy="50"
        r="48"
      />
      <circle
        ref={(el) => {
          rings.current[1] = el;
        }}
        className={styles.o2}
        cx="50"
        cy="50"
        r="39"
      />
      <circle className={styles.o3} cx="50" cy="50" r="29" />
      {BARS.map((b, i) => (
        <rect
          key={b.x}
          ref={(el) => {
            bars.current[i] = el;
          }}
          className={styles.bar}
          x={b.x}
          y={b.y}
          width="6"
          height={b.h}
          rx="3"
        />
      ))}
    </svg>
  );
}
