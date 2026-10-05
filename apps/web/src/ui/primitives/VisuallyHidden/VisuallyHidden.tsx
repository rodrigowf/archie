/** Content for screen readers only (spec 13 §3.5). */
import type { HTMLAttributes, ReactNode } from 'react';

export const visuallyHiddenClass = 'visually-hidden';

export interface VisuallyHiddenProps extends HTMLAttributes<HTMLSpanElement> {
  children: ReactNode;
}

export function VisuallyHidden({ className, children, ...rest }: VisuallyHiddenProps) {
  return (
    <span {...rest} className={className ? `${visuallyHiddenClass} ${className}` : visuallyHiddenClass}>
      {children}
    </span>
  );
}
