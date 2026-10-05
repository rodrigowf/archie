/**
 * Material Symbols Rounded icon as inline SVG (spec 13 §3.8). Only manifest icons exist: an
 * unknown name is a type error, and `filled` is only accepted for names with a FILL=1 variant.
 * Decorative (aria-hidden) unless `label` is given, which makes it role="img".
 */
import type { SVGAttributes } from 'react';
import { ICON_VIEWBOX, filledIconPaths, iconPaths, type FilledIconName, type IconName } from '@/ui/icons';
import styles from './Icon.module.css';

type IconBaseProps = Omit<SVGAttributes<SVGSVGElement>, 'name' | 'children'> & {
  /** Size in CSS px (default 24). */
  size?: number;
  /** Accessible name. Omit for decorative icons next to a text label. */
  label?: string;
};

export type IconProps = IconBaseProps & ({ name: IconName; filled?: false } | { name: FilledIconName; filled?: boolean });

export function Icon({ name, filled, size = 24, label, className, ...rest }: IconProps) {
  const d = filled ? filledIconPaths[name as FilledIconName] : iconPaths[name];
  return (
    <svg
      {...rest}
      className={className ? `${styles.icon} ${className}` : styles.icon}
      viewBox={ICON_VIEWBOX}
      width={size}
      height={size}
      focusable="false"
      data-icon={name}
      {...(label ? { role: 'img', 'aria-label': label } : { 'aria-hidden': true })}
    >
      <path d={d} />
    </svg>
  );
}
