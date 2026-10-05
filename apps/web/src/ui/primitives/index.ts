/**
 * Entry point of `@/ui/primitives` (W-02, spec 13 §3.5, §3.9): layout primitives, ScrollArea,
 * Icon, StateLayer, FocusRing, VisuallyHidden, Spinner. Global CSS for the class-based parts is in
 * src/styles/primitives.css (loaded by `@/styles`).
 */
export { Box, Center, Cluster, Inline, Split, Stack } from './layout/Layout';
export type { BoxProps, CenterProps, ClusterProps, InlineProps, SplitProps, StackProps } from './layout/Layout';
export type { Align, LayoutTag, SpaceKey } from './layout/types';
export { cx } from './layout/types';
export { wrapTextChildren } from './layout/wrapText';

export { ScrollArea } from './ScrollArea/ScrollArea';
export type { ScrollAreaHandle, ScrollAreaProps } from './ScrollArea/ScrollArea';
export { NEAR_BOTTOM_PX, QUIET_MS, ScrollController } from './ScrollArea/scrollController';
export type { AnchorRef, PreserveAnchorOptions, ScrollEnv } from './ScrollArea/scrollController';

export { Icon } from './Icon/Icon';
export type { IconProps } from './Icon/Icon';
export type { FilledIconName, IconName } from '@/ui/icons';

export { StateLayer, stateHostClass, stateLayerClass } from './StateLayer/StateLayer';
export type { ForcedState } from './StateLayer/StateLayer';
export { FocusRing, focusRingHostClass } from './FocusRing/FocusRing';
export type { FocusRingProps } from './FocusRing/FocusRing';
export { VisuallyHidden, visuallyHiddenClass } from './VisuallyHidden/VisuallyHidden';
export type { VisuallyHiddenProps } from './VisuallyHidden/VisuallyHidden';
export { Spinner } from './Spinner/Spinner';
export type { SpinnerProps } from './Spinner/Spinner';

/** Type-scale class for an M3 role (src/styles/typography.css), e.g. typeClass('title-medium'). */
export type TypeRoleClass =
  | 'display-large'
  | 'display-medium'
  | 'display-small'
  | 'headline-large'
  | 'headline-medium'
  | 'headline-small'
  | 'title-large'
  | 'title-medium'
  | 'title-small'
  | 'body-large'
  | 'body-medium'
  | 'body-small'
  | 'label-large'
  | 'label-medium'
  | 'label-small'
  | 'code';

export function typeClass(role: TypeRoleClass): string {
  return `type-${role}`;
}
