/**
 * Claude CLI sign-in state of the backend (inventory 01 §3.1, inv02 §1.11, spec 12 §8.1). Used by
 * the AuthGate at startup and by Settings → Account.
 *
 * Differences from the old gate (fixes): a failed status check is "unknown", not "signed out"
 * (the old app showed the sign-in screen whenever the backend was unreachable); server errors are
 * shown verbatim; status can be re-checked; credentials can be replaced while signed in (the
 * headless check does not look at expiry, so an expired token still reads "signed in").
 */
import { useStore } from 'zustand';
import { createStore } from 'zustand/vanilla';
import { api, errorMessage, type AuthStatus } from '@/services';
import { sessionStore } from '@/platform';

export type AuthPhase = 'idle' | 'checking' | 'signing-in' | 'saving';

export interface AuthState {
  readonly status: AuthStatus | null;
  readonly phase: AuthPhase;
  /** Verbatim message of the last failed status check (status stays as it was). */
  readonly checkError: string | null;
  /** Message of the last failed sign-in / credentials attempt. */
  readonly actionError: string | null;
  readonly checkedAt: number;
  /** "Not now" on the gate, for this browser tab only. */
  readonly gateDismissed: boolean;
}

export const GATE_DISMISSED_KEY = 'archie.authGateDismissed';

function initial(): AuthState {
  return {
    status: null,
    phase: 'idle',
    checkError: null,
    actionError: null,
    checkedAt: 0,
    gateDismissed: sessionStore.get(GATE_DISMISSED_KEY) === '1',
  };
}

export const authStore = createStore<AuthState>(initial);

export function useAuth<T>(selector: (s: AuthState) => T): T {
  return useStore(authStore, selector);
}

export function resetAuth(): void {
  authStore.setState(initial(), true);
}

let inFlight: Promise<AuthStatus | null> | null = null;

/** `GET /api/auth/status`. Never rejects; on failure the previous status is kept. */
export function checkAuth(): Promise<AuthStatus | null> {
  if (inFlight) return inFlight;
  authStore.setState({ phase: 'checking', checkError: null });
  inFlight = api.auth
    .status()
    .then((status) => {
      authStore.setState({ status, phase: 'idle', checkedAt: Date.now() });
      return status;
    })
    .catch((err: unknown) => {
      authStore.setState({ phase: 'idle', checkError: errorMessage(err) });
      return null;
    })
    .finally(() => {
      inFlight = null;
    });
  return inFlight;
}

/**
 * `POST /api/auth/login` (non-headless only): runs `claude setup-token` on the server and blocks
 * until it exits, so a browser window opens **on the server machine**. Resolves to whether the
 * backend is now signed in.
 */
export async function signIn(): Promise<boolean> {
  authStore.setState({ phase: 'signing-in', actionError: null });
  try {
    const status = await api.auth.login();
    authStore.setState({
      status,
      phase: 'idle',
      checkedAt: Date.now(),
      actionError: status.authenticated ? null : "Sign-in didn't finish on the server. Try again, or paste credentials.",
    });
    return status.authenticated;
  } catch (err) {
    authStore.setState({ phase: 'idle', actionError: errorMessage(err) });
    return false;
  }
}

export type CredentialsCheck = { ok: true; json: string } | { ok: false; error: string };

/** Client-side check before sending (mirrors `manager/auth.py`: `claudeAiOauth.accessToken`). */
export function checkCredentialsText(text: string): CredentialsCheck {
  const t = text.trim();
  if (!t) return { ok: false, error: 'Paste the contents of .credentials.json' };
  let parsed: unknown;
  try {
    parsed = JSON.parse(t);
  } catch {
    return { ok: false, error: "That isn't valid JSON. Copy the whole file, including the braces." };
  }
  const oauth = parsed && typeof parsed === 'object' ? (parsed as { claudeAiOauth?: unknown }).claudeAiOauth : undefined;
  const token = oauth && typeof oauth === 'object' ? (oauth as { accessToken?: unknown }).accessToken : undefined;
  if (typeof token !== 'string' || !token) return { ok: false, error: 'Invalid credentials: the file has no claudeAiOauth.accessToken.' };
  return { ok: true, json: t };
}

/** `POST /api/auth/credentials`. Resolves to whether the backend accepted them. */
export async function submitCredentials(text: string): Promise<boolean> {
  const check = checkCredentialsText(text);
  if (!check.ok) {
    authStore.setState({ actionError: check.error });
    return false;
  }
  authStore.setState({ phase: 'saving', actionError: null });
  try {
    const status = await api.auth.credentials(check.json);
    authStore.setState({
      status,
      phase: 'idle',
      checkedAt: Date.now(),
      actionError: status.authenticated ? null : "Invalid credentials: the server didn't accept them.",
    });
    return status.authenticated;
  } catch (err) {
    authStore.setState({ phase: 'idle', actionError: `Failed to set credentials: ${errorMessage(err)}` });
    return false;
  }
}

export function clearAuthError(): void {
  authStore.setState({ actionError: null });
}

export function dismissGate(): void {
  sessionStore.set(GATE_DISMISSED_KEY, '1');
  authStore.setState({ gateDismissed: true });
}

/** One line for Settings → Account. */
export function authSummary(s: Pick<AuthState, 'status' | 'phase' | 'checkError'>): string {
  if (s.phase === 'checking' && !s.status) return 'Claude · checking…';
  if (!s.status) return s.checkError ? "Claude · couldn't check" : 'Claude';
  return s.status.authenticated ? 'Claude · signed in' : 'Claude · not signed in';
}
