/**
 * Non-interactive labels from the mockups' chips board:
 *   <Tag>Claude</Tag>                                  provider tag (`.prov`: 20 dp, secondary container)
 *   <Tag variant="scope" icon="dns">Archie (server)</Tag>   settings scope (`.scope`: 28 dp)
 */
import type { HTMLAttributes, ReactNode } from 'react';
import { cx, type IconName } from '@/ui/primitives';
import { Glyph } from '../shared/Glyph';
import styles from './Chip.module.css';

export interface TagProps extends HTMLAttributes<HTMLSpanElement> {
  variant?: 'provider' | 'scope';
  icon?: IconName;
  children: ReactNode;
}

export function Tag({ variant = 'provider', icon, className, children, ...rest }: TagProps) {
  return (
    <span {...rest} className={cx(variant === 'scope' ? styles.scope : styles.provider, className)}>
      {icon ? <Glyph name={icon} size={16} className={styles.tagIcon} /> : null}
      <span>{children}</span>
    </span>
  );
}
