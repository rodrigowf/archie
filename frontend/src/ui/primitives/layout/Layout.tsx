/**
 * Layout primitives (spec 13 §2.1). No flex `gap` (Safari 14.1+): spacing is margin-based, from
 * src/styles/primitives.css, set per instance through custom properties.
 *
 * | Component | Rule                                                        | Use                         |
 * |-----------|-------------------------------------------------------------|-----------------------------|
 * | Stack     | column; `> * + * { margin-top }`                            | vertical lists of blocks    |
 * | Inline    | row, centered; `> * + * { margin-left }`; wraps text nodes  | icon + label, button rows   |
 * | Cluster   | wrapping row; half margins on an inner element; wraps text  | chips, wrapping toolbars    |
 * | Center    | max-width + auto margins (+ gutter)                         | the 840 dp message column   |
 * | Box       | padding only                                                | surfaces                    |
 * | Split     | row; last child pushed to the end; wraps text nodes         | header with trailing actions|
 */
import { createElement, forwardRef } from 'react';
import { alignItems, cx, space, withVars, type Align, type LayoutProps, type SpaceKey } from './types';
import { wrapTextChildren } from './wrapText';

export interface StackProps extends LayoutProps {
  /** Space between children (default 4 = 16 px). */
  space?: SpaceKey;
  /** Cross-axis alignment (default stretch). */
  align?: Align;
}

export const Stack = forwardRef<HTMLElement, StackProps>(function Stack(
  { as = 'div', space: s, align, className, style, children, ...rest },
  ref,
) {
  return createElement(
    as,
    {
      ...rest,
      ref,
      className: cx('stack', className),
      style: withVars({ '--stack-space': s && space(s) }, align ? { alignItems: alignItems[align], ...style } : style),
    },
    children,
  );
});

export interface InlineProps extends LayoutProps {
  /** Space between children (default 2 = 8 px). */
  space?: SpaceKey;
  /** Cross-axis alignment (default center). */
  align?: Align;
  /** Render as `inline-flex` instead of `flex`. */
  inline?: boolean;
}

export const Inline = forwardRef<HTMLElement, InlineProps>(function Inline(
  { as = 'div', space: s, align, inline, className, style, children, ...rest },
  ref,
) {
  const base = { ...(align ? { alignItems: alignItems[align] } : null), ...(inline ? { display: 'inline-flex' } : null), ...style };
  return createElement(
    as,
    { ...rest, ref, className: cx('inline', className), style: withVars({ '--inline-space': s && space(s) }, base) },
    ...wrapTextChildren(children),
  );
});

export interface ClusterProps extends LayoutProps {
  /** Space between items, both axes (default 2 = 8 px). */
  space?: SpaceKey;
  align?: Align;
  justify?: 'start' | 'center' | 'end';
}

const justifyContent = { start: 'flex-start', center: 'center', end: 'flex-end' } as const;

export const Cluster = forwardRef<HTMLElement, ClusterProps>(function Cluster(
  { as = 'div', space: s, align, justify, className, style, children, ...rest },
  ref,
) {
  const innerStyle = {
    ...(align ? { alignItems: alignItems[align] } : null),
    ...(justify ? { justifyContent: justifyContent[justify] } : null),
  };
  // A list Cluster renders <div class="cluster"><ul class="cluster-inner"><li/>…, so <li> stays
  // a direct child of its list.
  const isList = as === 'ul' || as === 'ol';
  return createElement(
    isList ? 'div' : as,
    { ...rest, ref, className: cx('cluster', className), style: withVars({ '--cluster-space': s && space(s) }, style) },
    createElement(isList ? as : 'div', { className: 'cluster-inner', style: innerStyle }, ...wrapTextChildren(children)),
  );
});

export interface CenterProps extends LayoutProps {
  /** Max content width in px (default 840, the message column). */
  max?: number;
  /** Inline padding on both sides. */
  gutter?: SpaceKey;
}

export const Center = forwardRef<HTMLElement, CenterProps>(function Center(
  { as = 'div', max, gutter, className, style, children, ...rest },
  ref,
) {
  return createElement(
    as,
    {
      ...rest,
      ref,
      className: cx('center', className),
      style: withVars({ '--center-max': max === undefined ? undefined : `${max}px`, '--center-gutter': gutter && space(gutter) }, style),
    },
    children,
  );
});

export interface BoxProps extends LayoutProps {
  /** Padding on all sides (default 4 = 16 px). */
  padding?: SpaceKey;
  /** Horizontal padding (overrides `padding` on the x axis). */
  padX?: SpaceKey;
  /** Vertical padding (overrides `padding` on the y axis). */
  padY?: SpaceKey;
}

export const Box = forwardRef<HTMLElement, BoxProps>(function Box(
  { as = 'div', padding, padX, padY, className, style, children, ...rest },
  ref,
) {
  const all = padding ?? '4';
  const value = padding === undefined && padX === undefined && padY === undefined ? undefined : `${space(padY ?? all)} ${space(padX ?? all)}`;
  return createElement(as, { ...rest, ref, className: cx('box', className), style: withVars({ '--box-padding': value }, style) }, children);
});

export interface SplitProps extends LayoutProps {
  /** Minimum space between children (default 2 = 8 px). */
  space?: SpaceKey;
  align?: Align;
}

export const Split = forwardRef<HTMLElement, SplitProps>(function Split(
  { as = 'div', space: s, align, className, style, children, ...rest },
  ref,
) {
  return createElement(
    as,
    {
      ...rest,
      ref,
      className: cx('split', className),
      style: withVars({ '--split-space': s && space(s) }, align ? { alignItems: alignItems[align], ...style } : style),
    },
    ...wrapTextChildren(children),
  );
});
