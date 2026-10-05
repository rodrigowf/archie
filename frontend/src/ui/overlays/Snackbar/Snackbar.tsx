/**
 * Snackbar + SnackbarHost (spec 13 §3.5; mockups `.snack`). Inverse surface, radius 8, one message,
 * an optional text action and close button. The host is a persistent `aria-live="polite"` region
 * (kept exposed while modals are open), shows one snackbar at a time and auto-hides it: 4 s, or
 * 8 s with an action, paused while hovered or focused. `duration: null` keeps it until dismissed.
 *
 * The app's queue lives in `stores/snackbar` (W-06); the host is controlled (`snackbar` +
 * `onDismiss`). `useSnackbarQueue` is a small local queue for galleries, tests and early wiring.
 */
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { useLatest } from '@/ui/a11y';
import { Button, IconButton } from '@/ui/controls';
import styles from './Snackbar.module.css';

export interface SnackbarAction {
  label: string;
  onAction: () => void;
}

export interface SnackbarData {
  id: string | number;
  message: string;
  action?: SnackbarAction;
  /** Show a close button (default: only when there is no auto-hide). */
  dismissible?: boolean;
  /** Auto-hide after ms; `null` never (default 4000, or 8000 with an action). */
  duration?: number | null;
}

export type SnackbarDismissReason = 'timeout' | 'action' | 'dismiss';

export const SNACKBAR_MS = 4000;
export const SNACKBAR_ACTION_MS = 8000;

export function snackbarDuration(s: SnackbarData): number | null {
  if (s.duration === null) return null;
  return s.duration ?? (s.action ? SNACKBAR_ACTION_MS : SNACKBAR_MS);
}

export interface SnackbarProps {
  snackbar: SnackbarData;
  onDismiss: (reason: SnackbarDismissReason) => void;
  className?: string;
  style?: CSSProperties;
}

/** One snackbar surface (no timer; the host owns timing). */
export function Snackbar({ snackbar, onDismiss, className, style }: SnackbarProps) {
  const closable = snackbar.dismissible ?? snackbarDuration(snackbar) === null;
  return (
    <div className={[styles.snack, className].filter(Boolean).join(' ')} style={style}>
      <span className={styles.message}>{snackbar.message}</span>
      {snackbar.action ? (
        <Button
          variant="text"
          className={styles.action}
          onClick={() => {
            snackbar.action?.onAction();
            onDismiss('action');
          }}
        >
          {snackbar.action.label}
        </Button>
      ) : null}
      {closable ? (
        <IconButton
          icon="close"
          size="small"
          aria-label="Dismiss"
          className={styles.close}
          onClick={() => {
            onDismiss('dismiss');
          }}
        />
      ) : null}
    </div>
  );
}

export interface SnackbarHostProps {
  snackbar: SnackbarData | null;
  onDismiss: (id: SnackbarData['id'], reason: SnackbarDismissReason) => void;
  /** Extra distance from the bottom in px (e.g. above the composer). */
  bottomOffset?: number;
  /** Render in place (gallery previews) instead of fixed to the window. */
  inline?: boolean;
}

export function SnackbarHost({ snackbar, onDismiss, bottomOffset = 0, inline }: SnackbarHostProps) {
  // Paused for one snackbar only: a new one starts its own timer.
  const [pausedId, setPausedId] = useState<SnackbarData['id'] | null>(null);
  const dismissRef = useLatest(onDismiss);
  const id = snackbar?.id;
  const ms = snackbar ? snackbarDuration(snackbar) : null;
  const paused = id !== undefined && pausedId === id;

  useEffect(() => {
    if (id === undefined || ms === null || paused) return undefined;
    const t = setTimeout(() => {
      dismissRef.current(id, 'timeout');
    }, ms);
    return () => {
      clearTimeout(t);
    };
  }, [id, ms, paused, dismissRef]);

  const pause = (): void => {
    setPausedId(id ?? null);
  };
  const resume = (e?: { currentTarget: HTMLElement; relatedTarget: EventTarget | null }): void => {
    if (e && e.relatedTarget instanceof Node && e.currentTarget.contains(e.relatedTarget)) return;
    setPausedId(null);
  };

  return (
    <div
      className={inline ? `${styles.host} ${styles.inlineHost}` : styles.host}
      style={inline ? undefined : { bottom: `calc(16px + env(safe-area-inset-bottom) + ${bottomOffset}px)` }}
      role="status"
      aria-live="polite"
      aria-atomic="true"
      data-a11y-keep=""
      onMouseEnter={pause}
      onMouseLeave={() => {
        resume();
      }}
      onFocus={pause}
      onBlur={resume}
    >
      {snackbar ? (
        <Snackbar
          key={snackbar.id}
          snackbar={snackbar}
          onDismiss={(reason) => {
            onDismiss(snackbar.id, reason);
          }}
        />
      ) : null}
    </div>
  );
}

/** Minimal FIFO queue: `show()` enqueues, the head is `current`, `dismiss(id)` advances. */
export function useSnackbarQueue(): {
  current: SnackbarData | null;
  show: (s: Omit<SnackbarData, 'id'> & { id?: SnackbarData['id'] }) => SnackbarData['id'];
  dismiss: (id: SnackbarData['id']) => void;
} {
  const [queue, setQueue] = useState<SnackbarData[]>([]);
  const seq = useRef(0);
  const show = useCallback((s: Omit<SnackbarData, 'id'> & { id?: SnackbarData['id'] }) => {
    const id = s.id ?? `snack-${++seq.current}`;
    setQueue((q) => [...q, { ...s, id }]);
    return id;
  }, []);
  const dismiss = useCallback((id: SnackbarData['id']) => {
    setQueue((q) => q.filter((s) => s.id !== id));
  }, []);
  return { current: queue[0] ?? null, show, dismiss };
}
