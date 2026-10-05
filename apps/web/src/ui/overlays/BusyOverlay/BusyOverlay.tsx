/**
 * Busy overlay primitive (spec 13 §3.5; inv02 F-12): covers its positioned parent (or the window
 * with `fixed`) while an operation runs, blocks presses, and reports progress as
 * `role="status"` with `aria-busy="true"`. W-11's host decides when to show it.
 */
import { Spinner } from '@/ui/primitives';
import styles from './BusyOverlay.module.css';

export interface BusyOverlayProps {
  /** Status text, e.g. "Rewinding…". */
  label: string;
  /** Cover the window instead of the positioned parent. */
  fixed?: boolean;
  className?: string;
}

export function BusyOverlay({ label, fixed, className }: BusyOverlayProps) {
  return (
    <div className={[styles.overlay, fixed ? styles.fixed : '', className].filter(Boolean).join(' ')}>
      <div className={styles.card} role="status" aria-busy="true" aria-live="polite">
        <Spinner size={24} />
        <span className={styles.label}>{label}</span>
      </div>
    </div>
  );
}
