/**
 * Keyboard focus ring as a child element (spec 13 §3.5), for hosts where the default outline from
 * base.css is clipped or must follow a large radius. The host needs `focusRingHostClass`; the ring
 * shows on `:focus-visible` (polyfilled as `.focus-visible` on compat) or `data-state="focus"`.
 */
import type { CSSProperties } from 'react';

export const focusRingHostClass = 'focus-ring-host';

export interface FocusRingProps {
  /** Draw inside the host (for hosts at the edge of a clipping container). */
  inward?: boolean;
  /** Corner radius of the ring (CSS length; default fully rounded). */
  radius?: string;
}

export function FocusRing({ inward, radius }: FocusRingProps) {
  const style = radius ? ({ '--focus-ring-radius': radius } as CSSProperties) : undefined;
  return <span className={inward ? 'focus-ring focus-ring-inward' : 'focus-ring'} style={style} aria-hidden="true" />;
}
