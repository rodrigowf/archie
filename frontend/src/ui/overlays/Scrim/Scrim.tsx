/**
 * Scrim behind modal overlays (M3: scrim color at 32 %, `--md-sys-color-scrim-overlay`). Explicit
 * offsets (never `inset`), blur only off low-end devices (spec 13 §2.7). Decorative: the overlay
 * itself carries the semantics, and Escape / Back close it for keyboard and screen-reader users.
 */
import styles from './Scrim.module.css';

export interface ScrimProps {
  /** A press on the scrim (closes dismissible overlays). */
  onPress?: () => void;
  className?: string;
}

export function Scrim({ onPress, className }: ScrimProps) {
  return (
    // Mouse/touch convenience only (aria-hidden); keyboard users close with Escape (overlay stack).
    <div
      className={className ? `${styles.scrim} ${className}` : styles.scrim}
      aria-hidden="true"
      data-scrim=""
      onClick={onPress}
    />
  );
}
