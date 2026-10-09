/**
 * Entry point of `@/features/auth` (W-13, spec 13 §3.6, §3.9).
 *
 *   <AuthGate>{app}</AuthGate>       startup status check; sign-in screen over the app when needed
 *   <AuthPanel host />               the Claude link sign-in / paste-credentials flows (the gate)
 *   checkAuth(), useAuth(selector)   status store shared by both
 */
export { AuthGate, SignInScreen, backendHost, type AuthGateProps } from './AuthGate';
export { AuthPanel, type AuthPanelProps } from './AuthPanel';
export {
  authStore,
  authSummary,
  checkAuth,
  cancelLinkSignIn,
  checkCredentialsText,
  clearAuthError,
  dismissGate,
  GATE_DISMISSED_KEY,
  linkFlowActive,
  pollLinkSignIn,
  resetAuth,
  signIn,
  startLinkSignIn,
  submitCredentials,
  submitLinkCode,
  useAuth,
  type AuthPhase,
  type AuthState,
  type CredentialsCheck,
} from './authStore';
