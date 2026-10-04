/**
 * The level orb (mockups `.orb`): three rings pulsing and four level bars. Bars follow the live
 * RMS (mic while listening, speaker while Archie speaks), polled at ~15 fps (10 fps on low-end,
 * spec 13 §2.7) and written straight to the DOM, so audio levels never re-render React.
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

export function LevelOrb({ tone, size = 60, level, still = false, className }: LevelOrbProps) {
  const bars = useRef<(SVGRectElement | null)[]>([]);
  const live = !!level && tone !== 'recon' && tone !== 'idle';

  useEffect(() => {
    if (!live || !level) return undefined;
    const period = isLowEnd() ? 100 : 66;
    let smooth = 0;
    const els = bars.current;
    const t = setInterval(() => {
      const v = visualLevel(level());
      smooth = v > smooth ? v : smooth * 0.7 + v * 0.3; // fast attack, slow release
      els.forEach((el, i) => {
        const b = BARS[i] as (typeof BARS)[number];
        // a CSS transform: the CSS transform-origin (50px 50px, the bars' common centre) applies
        if (el) el.style.transform = `scaleY(${(0.35 + 0.65 * Math.min(1, smooth * (0.6 + b.f * 0.6))).toFixed(3)})`;
      });
    }, period);
    return () => {
      clearInterval(t);
      els.forEach((el) => {
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
      className={cx(styles.orb, TONE_CLASS[tone], (still || tone === 'idle') && styles.orbStill, live && styles.orbLive, className)}
    >
      <circle className={styles.o1} cx="50" cy="50" r="48" />
      <circle className={styles.o2} cx="50" cy="50" r="39" />
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
