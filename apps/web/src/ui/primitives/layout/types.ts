import type { CSSProperties, HTMLAttributes, ReactNode } from 'react';

/** Spacing keys of the 4 dp grid (tokens: --app-space-N = N × 4 px). */
export type SpaceKey = '0' | '1' | '2' | '3' | '4' | '5' | '6' | '8' | '10' | '12' | '14' | '16';

export type LayoutTag =
  | 'div'
  | 'section'
  | 'article'
  | 'aside'
  | 'header'
  | 'footer'
  | 'main'
  | 'nav'
  | 'ul'
  | 'ol'
  | 'li'
  | 'span'
  | 'form'
  | 'dl';

export type Align = 'start' | 'center' | 'end' | 'stretch' | 'baseline';

export interface LayoutProps extends HTMLAttributes<HTMLElement> {
  /** Element to render (default `div`). */
  as?: LayoutTag;
  children?: ReactNode;
}

export const alignItems: Record<Align, CSSProperties['alignItems']> = {
  start: 'flex-start',
  center: 'center',
  end: 'flex-end',
  stretch: 'stretch',
  baseline: 'baseline',
};

export function space(key: SpaceKey): string {
  return `var(--app-space-${key})`;
}

/** Inline style with custom properties (React's CSSProperties type has no index for `--x`). */
export function withVars(vars: Record<string, string | undefined>, style?: CSSProperties): CSSProperties {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(vars)) if (v !== undefined) out[k] = v;
  return { ...(out as CSSProperties), ...style };
}

export function cx(...names: (string | false | null | undefined)[]): string {
  return names.filter(Boolean).join(' ');
}
