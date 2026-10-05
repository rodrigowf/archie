/**
 * Entry point of `@/ui/a11y` (W-04, spec 13 §3.5): accessibility infrastructure shared by the
 * overlays, the navigation components and the app shell.
 */
export { overlayStack, pushOverlay, resetOverlayStackForTests } from './overlayStack';
export type { OverlayCloseReason, OverlayHandle, OverlayOptions } from './overlayStack';
export { useOverlayLayer } from './useOverlayLayer';
export { useLatest } from './useLatest';
export type { OverlayLayerOptions } from './useOverlayLayer';
export { initialFocusTarget, useFocusTrap } from './useFocusTrap';
export type { FocusTrapOptions } from './useFocusTrap';
export { useReturnFocus } from './useReturnFocus';
export type { ReturnFocusOptions } from './useReturnFocus';
export { useOutsidePress } from './useDismiss';
/** Alias named in spec 13 §3.5. */
export { useOutsidePress as useDismiss } from './useDismiss';
export type { DismissOptions } from './useDismiss';
export { rovingItems, useRovingFocus } from './useRovingFocus';
export type { RovingFocusOptions, RovingOrientation } from './useRovingFocus';
export { isScrollLocked, lockScroll, useScrollLock } from './useScrollLock';
export { hideOthers } from './hideOthers';
export { announce, resetAnnouncerForTests } from './announce';
export type { Politeness } from './announce';
export { createTypeahead, isTypeaheadKey, TYPEAHEAD_RESET_MS } from './typeahead';
export type { Typeahead } from './typeahead';
export { activeElement, focusableIn, focusElement, tabbableIn } from './focus';
export {
  classifySwipe,
  createDragTracker,
  LONG_PRESS_MS,
  SWIPE_MIN_PX,
  SWIPE_MIN_VELOCITY,
  useDrag,
  useLongPress,
  useSwipe,
} from './gestures';
export type { DragHandlers, DragOptions, DragSample, LongPressOptions, Point } from './gestures';
