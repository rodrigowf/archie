/**
 * `AuthGate{children}` (spec 13 §3.9, inv02 §1.11). Checks `GET /api/auth/status` once at
 * startup. When the backend's Claude CLI is not signed in, the sign-in screen covers the app
 * (modal: the app stays mounted underneath, aria-hidden, so nothing remounts after sign-in).
 *
 * Changed from the old gate: the app renders while the check runs (the non-headless check can
 * take 10 s); a failed check never shows the sign-in screen (the old one did whenever the backend
 * was unreachable); "Not now" dismisses it for this browser tab, because Archie and the Qwen /
 * Gemini harnesses work without Claude credentials. Settings → Account offers the same flows.
 */
import { useEffect, useRef, type ReactNode } from 'react';
import { getEnv } from '@/services';
import { useOverlayLayer } from '@/ui/a11y';
import { Button } from '@/ui/controls';
import { Portal } from '@/ui/overlays';
import { Icon, ScrollArea } from '@/ui/primitives';
import { AuthPanel } from './AuthPanel';
import { checkAuth, dismissGate, useAuth } from './authStore';
import styles from './auth.module.css';

/** Host of the backend, for copy ("…opens on 192.168.0.200"). */
export function backendHost(): string {
  try {
    const base = getEnv().baseUrl;
    if (base) return new URL(base).hostname;
  } catch {
    // relative or invalid base: fall through
  }
  return typeof location !== 'undefined' && location.hostname ? location.hostname : 'the server';
}

export interface AuthGateProps {
  children: ReactNode;
  /** Run the status check on mount (default true; tests and the gallery drive the store). */
  check?: boolean;
}

export function AuthGate({ children, check = true }: AuthGateProps) {
  const status = useAuth((s) => s.status);
  const dismissed = useAuth((s) => s.gateDismissed);
  useEffect(() => {
    if (check) void checkAuth();
  }, [check]);
  const blocked = !!status && !status.authenticated && !dismissed;
  return (
    <>
      {children}
      {blocked ? (
        <Portal>
          <SignInScreen />
        </Portal>
      ) : null}
    </>
  );
}

/** The full-screen sign-in surface (also rendered inline by the gallery). */
export function SignInScreen({ inline = false }: { inline?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const phase = useAuth((s) => s.phase);
  useOverlayLayer({ open: !inline, onClose: () => undefined, containerRef: ref, modal: true, escape: false, initialFocus: heading });
  const host = backendHost();
  return (
    <div
      ref={ref}
      className={inline ? `${styles.gate} ${styles.gateInline}` : styles.gate}
      role="dialog"
      aria-modal={inline ? undefined : true}
      aria-labelledby="auth-gate-title"
    >
      <ScrollArea className={styles.gateScroll}>
        <div className={styles.gateCard}>
          <Icon name="account_circle" size={40} className={styles.gateIcon} />
          <h1 id="auth-gate-title" ref={heading} tabIndex={-1} className={styles.gateTitle}>
            Sign in to Claude
          </h1>
          <p className={styles.gateText}>
            Agent sessions run Claude Code on <b>{host}</b>, and it isn&apos;t signed in yet.
          </p>
          <AuthPanel host={host} />
          <div className={styles.gateFooter}>
            <Button variant="text" icon="refresh" disabled={phase !== 'idle'} onClick={() => void checkAuth()}>
              Check again
            </Button>
            <Button variant="text" disabled={phase === 'signing-in' || phase === 'saving'} onClick={dismissGate}>
              Not now
            </Button>
          </div>
        </div>
      </ScrollArea>
    </div>
  );
}
