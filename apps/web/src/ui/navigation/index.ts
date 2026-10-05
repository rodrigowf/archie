/**
 * Entry point of `@/ui/navigation` (W-04, spec 13 §3.5, §3.9): rail, drawer, tabs (the base of the
 * W-07 session tab strip), top app bar and tree.
 */
export { NavigationRail } from './NavigationRail/NavigationRail';
export type { NavigationRailProps, RailDestination } from './NavigationRail/NavigationRail';
export { NavigationDrawer, NavigationDrawerHeadline, NavigationDrawerItem } from './NavigationDrawer/NavigationDrawer';
export type { NavigationDrawerItemProps, NavigationDrawerProps } from './NavigationDrawer/NavigationDrawer';
export { TabPanel, Tabs } from './Tabs/Tabs';
export type { TabItem, TabPanelProps, TabsProps } from './Tabs/Tabs';
export { AllTabsMenu, filterTabs } from './Tabs/AllTabsMenu';
export type { AllTabsMenuProps } from './Tabs/AllTabsMenu';
export { matchTabShortcut, resolveTabShortcut, useTabShortcuts } from './Tabs/shortcuts';
export type { TabShortcut, TabShortcutsOptions } from './Tabs/shortcuts';
export { TopAppBar } from './TopAppBar/TopAppBar';
export type { TopAppBarProps } from './TopAppBar/TopAppBar';
export { flattenVisible, Tree } from './Tree/Tree';
export type { TreeNode, TreeProps } from './Tree/Tree';
