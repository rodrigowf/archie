/**
 * Archie (server) → Account: the Claude CLI sign-in of the backend, with the AuthGate flows
 * (inv02 §1.11; spec 12 §8.1 "offer the credential-paste flow on headless backends").
 * Credentials can be replaced while signed in: the headless check only looks for an access token,
 * not its expiry, so an expired token still reads "signed in".
 */
import { useEffect } from 'react';
import { AuthPanel, backendHost, checkAuth, useAuth } from '@/features/auth';
import { Button, Disclosure } from '@/ui/controls';
import { Field, FieldStack, Notice } from '../parts';
import styles from '../settings.module.css';

export function AccountPage() {
  const status = useAuth((s) => s.status);
  const phase = useAuth((s) => s.phase);
  const checkError = useAuth((s) => s.checkError);
  useEffect(() => {
    void checkAuth();
  }, []);
  const host = backendHost();
  const state = !status ? (phase === 'checking' ? 'Checking…' : 'Unknown') : status.authenticated ? 'Signed in' : 'Not signed in';
  return (
    <>
      {checkError ? (
        <Notice tone="error" title="Couldn't check the sign-in">
          {checkError}
        </Notice>
      ) : null}
      <FieldStack label="Claude Code on the server">
        <Field>
          <dl className={styles.kv}>
            <div>
              <dt>Status</dt>
              <dd data-auth-state={status ? (status.authenticated ? 'in' : 'out') : 'unknown'}>{state}</dd>
            </div>
            <div>
              <dt>Server</dt>
              <dd>{host}</dd>
            </div>
            <div>
              <dt>Sign-in method</dt>
              <dd>{!status ? '—' : status.headless ? 'Paste credentials (no screen on the server)' : 'Sign-in window on the server'}</dd>
            </div>
          </dl>
          <div className={styles.actionsRow}>
            <Button variant="text" icon="refresh" loading={phase === 'checking'} onClick={() => void checkAuth()}>
              Check again
            </Button>
          </div>
        </Field>
      </FieldStack>
      {status && !status.authenticated ? (
        <FieldStack label="Sign in">
          <Field>
            <AuthPanel host={host} />
          </Field>
        </FieldStack>
      ) : null}
      {status?.authenticated ? (
        <FieldStack>
          <div className={styles.field}>
            <Disclosure summary="Replace credentials" icon="content_paste">
              <p className={styles.help}>Use this when agent sessions fail with “Invalid authentication credentials”: the token may have expired.</p>
              <AuthPanel host={host} startWithPaste />
            </Disclosure>
          </div>
        </FieldStack>
      ) : null}
    </>
  );
}
