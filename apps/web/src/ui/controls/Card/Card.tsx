/**
 * M3 card (spec 13 §3.5 W-03): filled (surface-container-highest), elevated
 * (surface-container-low + level 1) or outlined (outline-variant), 12 dp radius.
 *
 *   <Card variant="outlined">…</Card>                 static container (div/section/article/li)
 *   <Card onClick={open}>…</Card>                      whole card is a <button> (phrasing content only)
 *   <Card href={url}>…</Card>                          whole card is a link
 *
 * An actionable card renders a real button/link with a state layer and focus ring (no
 * div-with-onClick). Put nested actions beside it, not inside it.
 */
import { createElement, forwardRef, type HTMLAttributes, type MouseEvent, type ReactNode, type Ref } from 'react';
import { cx } from '@/ui/primitives';
import { guardClick } from '../shared/hooks';
import styles from './Card.module.css';

export type CardVariant = 'filled' | 'elevated' | 'outlined';

export interface CardProps extends HTMLAttributes<HTMLElement> {
  variant?: CardVariant;
  /** Element for a static card (default div). */
  as?: 'div' | 'section' | 'article' | 'li' | 'aside';
  /** Makes the whole card a link. */
  href?: string;
  target?: string;
  rel?: string;
  /** Inner padding (default 16 px); `none` for media-first cards. */
  padding?: 'none' | 'normal';
  disabled?: boolean;
  /** Gallery only. */
  'data-state'?: string;
  children?: ReactNode;
}

export const Card = forwardRef<HTMLElement, CardProps>(function Card(
  { variant = 'filled', as = 'div', href, target, rel, padding = 'normal', disabled = false, className, onClick, children, ...rest },
  ref,
) {
  const interactive = href !== undefined || onClick !== undefined;
  const cls = cx(
    styles.card,
    styles[variant],
    padding === 'none' && styles.flush,
    interactive && styles.interactive,
    interactive && 'has-state-layer',
    className,
  );
  if (href !== undefined) {
    return (
      <a
        {...rest}
        ref={ref as Ref<HTMLAnchorElement>}
        href={disabled ? undefined : href}
        target={target}
        rel={rel ?? (target === '_blank' ? 'noopener noreferrer' : undefined)}
        aria-disabled={disabled || undefined}
        className={cls}
        onClick={onClick as ((e: MouseEvent<HTMLAnchorElement>) => void) | undefined}
      >
        {children}
      </a>
    );
  }
  if (onClick !== undefined) {
    return (
      <button
        {...rest}
        ref={ref as Ref<HTMLButtonElement>}
        type="button"
        aria-disabled={disabled || undefined}
        className={cls}
        onClick={guardClick<HTMLButtonElement>(disabled, onClick as (e: MouseEvent<HTMLButtonElement>) => void)}
      >
        {children}
      </button>
    );
  }
  return createElement(as, { ...rest, ref, className: cls }, children);
});
