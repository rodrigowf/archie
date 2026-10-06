/**
 * The sign-in flows (inv02 §1.11), shared by the AuthGate screen and Settings → Account:
 *
 * - **With a browser on the server** (not headless): "Sign in with Claude" runs
 *   `claude setup-token` on the server (`POST /api/auth/login`, which blocks until it exits);
 *   "Paste credentials instead" opens the manual flow.
 * - **Headless server**, or the manual view: paste `~/.claude/.credentials.json` from a signed-in
 *   machine (`POST /api/auth/credentials`), with an optional link to the Claude Console.
 */
import { useEffect, useRef, useState } from 'react';
import { showSnackbar } from '@/stores';
import { Button, TextField } from '@/ui/controls';
import { Icon } from '@/ui/primitives';
import { clearAuthError, signIn, submitCredentials, useAuth } from './authStore';
import styles from './auth.module.css';

export interface AuthPanelProps {
  /** Server host for the copy ("A browser window opens on 192.168.0.200"). */
  host: string;
  /** Start in the paste view even when the server could open a browser (Settings → "Replace credentials"). */
  startWithPaste?: boolean;
  /** Called after a successful sign-in or credentials save. */
  onSignedIn?: () => void;
}

export function AuthPanel({ host, startWithPaste = false, onSignedIn }: AuthPanelProps) {
  const status = useAuth((s) => s.status);
  const phase = useAuth((s) => s.phase);
  const error = useAuth((s) => s.actionError);
  const headless = status?.headless === true;
  const [pasteChosen, setPaste] = useState(startWithPaste);
  const paste = pasteChosen || headless;
  const [text, setText] = useState('');
  const field = useRef<HTMLTextAreaElement | HTMLInputElement>(null);

  useEffect(() => () => clearAuthError(), []);

  const done = (): void => {
    showSnackbar('Signed in to Claude', { durationMs: 2500 });
    setText('');
    onSignedIn?.();
  };

  if (!paste) {
    return (
      <div className={styles.panel}>
        <p className={styles.lead}>Sign in with your Claude account. A sign-in window opens on <b>{host}</b>; finish there and this page updates.</p>
        {phase === 'signing-in' ? (
          <p className={styles.waiting} role="status">
            <Icon name="hourglass_top" size={18} /> Waiting for sign-in to finish on {host}…
          </p>
        ) : null}
        {error ? (
          <p className={styles.error} role="alert">
            {error}
          </p>
        ) : null}
        <div className={styles.actions}>
          <Button
            variant="filled"
            icon="account_circle"
            loading={phase === 'signing-in'}
            onClick={() => {
              void signIn().then((ok) => {
                if (ok) done();
              });
            }}
          >
            Sign in with Claude
          </Button>
          <Button
            variant="text"
            icon="content_paste"
            disabled={phase === 'signing-in'}
            onClick={() => {
              clearAuthError();
              setPaste(true);
            }}
          >
            Paste credentials instead
          </Button>
        </div>
      </div>
    );
  }

  const submit = (): void => {
    void submitCredentials(text).then((ok) => {
      if (ok) done();
      else field.current?.focus();
    });
  };

  return (
    <form
      className={styles.panel}
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <ol className={styles.steps}>
        <li>
          On a computer where Claude Code is signed in, open <code>~/.claude/.credentials.json</code>.
        </li>
        <li>Copy the whole file and paste it below.</li>
      </ol>
      {headless ? (
        <p className={styles.note}>
          <Icon name="info" size={16} /> {host} runs without a screen, so it can't open a sign-in window.
        </p>
      ) : null}
      <TextField
        ref={field}
        label="Credentials JSON"
        multiline
        rows={5}
        maxRows={10}
        value={text}
        spellCheck={false}
        autoCapitalize="off"
        autoComplete="off"
        onValueChange={(v) => {
          setText(v);
          if (error) clearAuthError();
        }}
        error={error ?? undefined}
        supportingText={error ? undefined : 'Saved on the server as .credentials.json (only readable by the server user).'}
      />
      <div className={styles.actions}>
        <Button type="submit" variant="filled" icon="check" loading={phase === 'saving'} disabled={!text.trim()}>
          Set credentials
        </Button>
        {status?.auth_url ? (
          <Button variant="text" trailingIcon="open_in_new" onClick={() => window.open(status.auth_url ?? '', '_blank', 'noopener,noreferrer')}>
            Claude Console
          </Button>
        ) : null}
        {!headless && !startWithPaste ? (
          <Button
            variant="text"
            icon="arrow_back"
            onClick={() => {
              clearAuthError();
              setPaste(false);
            }}
          >
            Back
          </Button>
        ) : null}
      </div>
    </form>
  );
}
