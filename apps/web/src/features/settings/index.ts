/**
 * Entry point of `@/features/settings` (W-13, spec 13 §3.6, §3.9). Light: the screen and the
 * session sheet load their pages lazily (spec 13 §5.4 initial-JS budget).
 *
 *   <SettingsScreen page? onNavigate? />                 IA §7 settings (two-pane / pushed pages)
 *   <SessionSettingsSheet localId open onClose />       per-session settings (side / bottom sheet)
 *   <SessionSettingsHost onOpenSettings? />              mounts the sheet for openSessionSettings()
 *   openSessionSettings(localId)                         the ⋮ menu's "Session settings"
 *   <AppearanceEffects />                                applies text size (mount once)
 */
export { SettingsScreen, SessionSettingsSheet, SessionSettingsHost, preloadSettings, type SettingsScreenProps } from './lazy';
export { closeSessionSettings, openSessionSettings, sessionSettingsStore, useSessionSettingsTarget } from './session/sessionSettingsStore';
export { AppearanceEffects, applyTextSize, TEXT_SCALE } from './effects';
export { DEFAULT_PAGE, findSettingsPage, SETTINGS_PAGES, settingsPageTitle, type SettingsPageDef, type SettingsPageId } from './pages';
export type { SessionSettingsSheetProps } from './session/SessionSettingsSheet';
