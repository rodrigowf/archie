/**
 * Entry point of `@/ui/overlays` (W-04, spec 13 §3.5, §3.9). Every overlay follows the overlay
 * contract (portal, focus trap / return focus, Escape on the topmost only, outside press, Back,
 * background aria-hidden, scroll lock); see `@/ui/a11y` `useOverlayLayer`.
 */
export { getOverlayRoot, OVERLAY_ROOT_ID, Portal } from './Portal/Portal';
export type { PortalProps } from './Portal/Portal';
export { Scrim } from './Scrim/Scrim';
export type { ScrimProps } from './Scrim/Scrim';
export { pointAnchor, Popover } from './Popover/Popover';
export type { Placement, PopoverAnchor, PopoverProps, VirtualAnchor } from './Popover/Popover';
export { Menu, MenuItem, MenuLabel, MenuList, MenuSeparator } from './Menu/Menu';
export type { MenuItemProps, MenuListProps, MenuProps } from './Menu/Menu';
export { ConfirmDialog, Dialog } from './Dialog/Dialog';
export type { ConfirmDialogProps, DialogProps } from './Dialog/Dialog';
export { BottomSheet, SHEET_DISMISS_FRACTION, SHEET_DISMISS_VELOCITY, shouldDismissSheet } from './BottomSheet/BottomSheet';
export type { BottomSheetProps } from './BottomSheet/BottomSheet';
export { SideSheet } from './SideSheet/SideSheet';
export type { SideSheetProps } from './SideSheet/SideSheet';
export { Snackbar, SNACKBAR_ACTION_MS, SNACKBAR_MS, snackbarDuration, SnackbarHost, useSnackbarQueue } from './Snackbar/Snackbar';
export type { SnackbarAction, SnackbarData, SnackbarDismissReason, SnackbarHostProps, SnackbarProps } from './Snackbar/Snackbar';
export { Tooltip, TOOLTIP_DELAY_MS, TOOLTIP_TOUCH_MS } from './Tooltip/Tooltip';
export type { TooltipProps } from './Tooltip/Tooltip';
export { BusyOverlay } from './BusyOverlay/BusyOverlay';
export type { BusyOverlayProps } from './BusyOverlay/BusyOverlay';
export type { OverlayCloseReason } from '@/ui/a11y';
