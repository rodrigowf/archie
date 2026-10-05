/**
 * Small top app bar (spec 13 §3.5, IA §5; mockups `.appbar`, `.ttl`): same surface as the content,
 * no divider (R1). Leading navigation button, a title that can be a button (Compact: opens the
 * session switcher; ⌄/⌃ shows its state) with a live subtitle line, and trailing actions.
 * Horizontal swipe on the title (touch) reports `onTitleSwipe` (switch sessions; IA §5).
 */
import { useRef, type ReactNode } from 'react';
import { useSwipe } from '@/ui/a11y';
import { Icon, wrapTextChildren } from '@/ui/primitives';
import styles from './TopAppBar.module.css';

export interface TopAppBarProps {
  /** Leading navigation (☰ or ←), usually an icon button. */
  leading?: ReactNode;
  title: ReactNode;
  /** Live status line under the title (e.g. a status icon + "Thinking…"). */
  subtitle?: ReactNode;
  /** Icon/mark before the title. */
  titleIcon?: ReactNode;
  /** Makes the title a button (with a ⌄ indicator). */
  onTitleClick?: () => void;
  /** Accessible name of the title button ("Switch session"); the visible title follows it. */
  titleButtonLabel?: string;
  /** The menu/sheet the title opens is showing (⌃ indicator, aria-expanded). */
  titleExpanded?: boolean;
  onTitleSwipe?: (direction: 'left' | 'right') => void;
  /** Trailing action buttons. */
  actions?: ReactNode;
  /** `small` (default) or `plain` (a larger, non-interactive title for full screens). */
  variant?: 'small' | 'plain';
  className?: string;
}

export function TopAppBar({
  leading,
  title,
  subtitle,
  titleIcon,
  onTitleClick,
  titleButtonLabel,
  titleExpanded,
  onTitleSwipe,
  actions,
  variant = 'small',
  className,
}: TopAppBarProps) {
  const titleRef = useRef<HTMLElement | null>(null);
  useSwipe(
    titleRef,
    (dir) => {
      onTitleSwipe?.(dir);
    },
    { enabled: !!onTitleSwipe },
  );

  const text = (
    <span className={styles.texts}>
      <span className={variant === 'plain' ? styles.plainTitle : styles.main}>
        <span className={styles.titleText}>{title}</span>
        {onTitleClick ? <Icon name={titleExpanded ? 'arrow_drop_up' : 'arrow_drop_down'} className={styles.drop} /> : null}
      </span>
      {subtitle ? <span className={styles.sub}>{wrapTextChildren(subtitle)}</span> : null}
    </span>
  );

  return (
    <header className={[styles.bar, className].filter(Boolean).join(' ')}>
      {leading ? <div className={styles.leading}>{leading}</div> : <span className={styles.edge} />}
      {onTitleClick ? (
        <button
          ref={(el) => {
            titleRef.current = el;
          }}
          type="button"
          className={`${styles.title} ${styles.titleButton} has-state-layer`}
          aria-haspopup="dialog"
          aria-expanded={titleExpanded ?? false}
          onClick={onTitleClick}
        >
          {titleIcon ? <span className={styles.icon}>{titleIcon}</span> : null}
          {titleButtonLabel ? <span className="visually-hidden">{titleButtonLabel}: </span> : null}
          {text}
        </button>
      ) : (
        <div
          ref={(el) => {
            titleRef.current = el;
          }}
          className={styles.title}
        >
          {titleIcon ? <span className={styles.icon}>{titleIcon}</span> : null}
          {variant === 'plain' ? <h1 className={styles.heading}>{text}</h1> : text}
        </div>
      )}
      {actions ? <div className={styles.actions}>{actions}</div> : null}
    </header>
  );
}
