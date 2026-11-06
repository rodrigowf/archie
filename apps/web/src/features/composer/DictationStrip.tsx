/**
 * The control row under a live transcript (mockup `composer-dictation/`).
 *
 * In the dictation chunk, not in `parts.tsx`: it is only ever rendered while a dictation is
 * running, so it has no claim on the initial bundle.
 */
import { useEffect, useState, type ReactNode } from 'react';
import { IconButton } from '@/ui/controls';
import { Spinner } from '@/ui/primitives';
import { formatClock } from './logic';
import styles from './Composer.module.css';

export interface DictationStripProps {
  readonly startedAt: number;
  /** 0–1 mic level, polled while listening. */
  readonly level: () => number;
  /** The last words are still coming back from the transcriber. */
  readonly flushing: boolean;
  readonly onStop: () => void;
  readonly onCancel: () => void;
}

const METER_BARS = [0.55, 1, 0.8, 0.45];

/**
 * The control row under a live transcript: discard, level + elapsed, Stop, and (supplied by the
 * composer) Send. Send sits in the primary slot because it is the press that finishes the
 * errand — it stops and sends in one go.
 */
export function DictationStrip({ startedAt, level, flushing, onStop, onCancel, children }: DictationStripProps & { readonly children?: ReactNode }) {
  const [now, setNow] = useState(() => Date.now());
  const [lvl, setLvl] = useState(0);
  useEffect(() => {
    if (flushing) return undefined;
    const t = setInterval(() => {
      setNow(Date.now());
      setLvl(level());
    }, 100);
    return () => {
      clearInterval(t);
    };
  }, [flushing, level]);
  return (
    <div className={styles.dictRow}>
      <IconButton icon="close" aria-label="Discard dictation" onClick={onCancel} />
      <span className={styles.dictChip} role="status" aria-live="off">
        {flushing ? (
          <Spinner size={16} />
        ) : (
          <span className={styles.dictMeter} aria-hidden="true">
            {METER_BARS.map((peak, i) => (
              <i key={i} style={{ transform: `scaleY(${Math.max(0.2, Math.min(1, lvl * peak * 1.6 + 0.2))})` }} />
            ))}
          </span>
        )}
        <span className={styles.dictClock}>{formatClock(now - startedAt)}</span>
      </span>
      <span className={styles.dictSpacer} />
      {flushing ? null : <IconButton icon="stop" filled variant="tonal" aria-label="Stop dictation" onClick={onStop} />}
      {children}
    </div>
  );
}
