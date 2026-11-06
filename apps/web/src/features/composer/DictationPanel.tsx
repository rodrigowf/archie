/**
 * The composer while it is listening (mockup `composer-dictation/` §2 option A).
 *
 * A column: the transcript takes the full width — it is what you are about to edit, so it has to
 * be readable — and the controls sit beneath it. The one-row composer returns the moment
 * dictation stops.
 *
 * The lazy target of `dictationLazy.tsx` — nothing in the initial bundle may import this file
 * directly, or the split is undone. It is only ever on screen during a dictation, and the chunk is
 * fetched when the mic is pressed, so by the time it renders it is already there.
 */
import { useLayoutEffect, useRef, type ReactNode } from 'react';
import { DictationStrip } from './DictationStrip';
import styles from './Composer.module.css';

export interface DictationPanelProps {
  /** Text already in the field when dictation started; the new words append to it. */
  readonly base: string;
  readonly committed: string;
  readonly partial: string;
  /** Waiting for the last words after Stop. */
  readonly flushing: boolean;
  readonly startedAt: number;
  readonly level: () => number;
  readonly onStop: () => void;
  readonly onCancel: () => void;
  /** The primary button, so Send keeps its usual place and shape. */
  readonly children?: ReactNode;
}

export default function DictationPanel({
  base,
  committed,
  partial,
  flushing,
  startedAt,
  level,
  onStop,
  onCancel,
  children,
}: DictationPanelProps) {
  const textRef = useRef<HTMLDivElement>(null);
  const live = (committed + partial).trim();

  // Keep the newest words in view as they stream in.
  useLayoutEffect(() => {
    const el = textRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [committed, partial]);

  return (
    <div className={styles.dictBody}>
      <div ref={textRef} className={styles.dictText} role="status" aria-live="polite" aria-label="Dictated text">
        {base ? `${base.replace(/\s+$/, '')} ` : ''}
        {committed}
        <i className={styles.dictPartial}>{partial}</i>
        {!live && !flushing ? <span className={styles.dictPlaceholder}>Listening…</span> : null}
        {flushing && !live ? <span className={styles.dictPlaceholder}>Transcribing…</span> : null}
        {flushing ? null : <span className={styles.dictCaret} aria-hidden="true" />}
      </div>
      <DictationStrip startedAt={startedAt} level={level} flushing={flushing} onStop={onStop} onCancel={onCancel}>
        {children}
      </DictationStrip>
    </div>
  );
}
