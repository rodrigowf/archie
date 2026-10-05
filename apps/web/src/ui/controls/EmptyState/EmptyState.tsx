/**
 * Empty state (spec 13 §3.5 W-03; mockups `.empty`): centered media, title, description and
 * actions. `variant="hero"` is the Archie greeting size (30/38); default uses headline-small.
 *
 *   <EmptyState icon="forum" title="No conversations yet" description="…">
 *     <Button icon="add">New session</Button>
 *   </EmptyState>
 */
import type { HTMLAttributes, ReactNode } from 'react';
import { cx, type IconName } from '@/ui/primitives';
import { Glyph } from '../shared/Glyph';
import styles from './EmptyState.module.css';

export interface EmptyStateProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title'> {
  icon?: IconName;
  /** Custom media (an illustration or the Archie mark) instead of `icon`. */
  media?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  variant?: 'default' | 'hero';
  /** Heading level of the title (default 2). */
  headingLevel?: 2 | 3 | 4;
  /** Actions (buttons, suggestion chips). */
  children?: ReactNode;
}

export function EmptyState({
  icon,
  media,
  title,
  description,
  variant = 'default',
  headingLevel = 2,
  className,
  children,
  ...rest
}: EmptyStateProps) {
  const Heading = `h${String(headingLevel)}` as 'h2' | 'h3' | 'h4';
  return (
    <div {...rest} className={cx(styles.empty, variant === 'hero' && styles.hero, className)}>
      {media ??
        (icon ? (
          <span className={styles.icon} aria-hidden="true">
            <Glyph name={icon} size={variant === 'hero' ? 40 : 32} />
          </span>
        ) : null)}
      <Heading className={styles.title}>{title}</Heading>
      {description ? <p className={styles.description}>{description}</p> : null}
      {children ? <div className={styles.actions}>{children}</div> : null}
    </div>
  );
}
