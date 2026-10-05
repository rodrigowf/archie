/**
 * Entry point of `@/app` (W-07): the app root and the shell actions later packages call
 * (`openDocument` from W-14's panes, the new/close/rename flows from W-11's menus).
 */
export { App, type AppProps } from './App';
export { AppShell } from './AppShell';
export { useWindowClass, currentWindowClass, type WindowClass } from './useWindowClass';
export { navigate, useRoute, parseHash, formatRoute, type Route } from './navigation/route';
export {
  newArchie,
  newAgent,
  openDocument,
  openFromHistory,
  focusTab,
  requestCloseTab,
  requestRename,
  type DocumentKind,
} from './shell/actions';
export { BINDINGS } from './keyboard/bindings';
export { COMPOSER_FIELD_ATTR } from './keyboard/useAppKeyboard';
export { ArchieMark } from './shell/ArchieMark';
