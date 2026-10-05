/**
 * Composer parts (W-11): context ring, primary button, queue tray, recording strip, read-only
 * bar and the slash-command list. Kept small and prop-driven so they test and gallery in isolation.
 */
import { useEffect, useState } from 'react';
import { api, errorMessage, type SkillInfo } from '@/services';
import { Button, CircularProgress, IconButton, List, ListItem } from '@/ui/controls';
import { Dialog, Tooltip } from '@/ui/overlays';
import { Icon, Spinner, cx } from '@/ui/primitives';
import { PRIMARY_LABEL, ringTone, formatClock, type PrimaryMode } from './logic';
import type { ContextUsage } from '@/protocol';
import styles from './Composer.module.css';

/* ───────────────────────── context ring (spec 12 §6.4, inv02 F-10) ───────────────────────── */

export interface ContextRingProps {
  readonly usage: ContextUsage;
  readonly disabled: boolean;
  readonly disabledReason?: string;
  readonly onCompact: () => void;
}

/** `NN%` of the context used (`?` unknown); caution ≥ 50 %, warning ≥ 80 %; tap → Compact. */
export function ContextRing({ usage, disabled, disabledReason, onCompact }: ContextRingProps) {
  const pct = usage.percent;
  const text = pct === null ? '?' : `${Math.min(100, pct)}%`;
  const label = pct === null ? 'Context use unknown. Compact context' : `Context ${Math.min(100, pct)}% used. Compact context`;
  const tip = disabled ? (disabledReason ?? label) : pct === null ? 'Compact context' : `Context ${Math.min(100, pct)}% used · tap to compact`;
  return (
    <Tooltip label={tip} inline>
      <button
        type="button"
        className={cx(styles.ring, 'has-state-layer')}
        data-level={usage.level}
        data-unknown={pct === null ? '' : undefined}
        aria-label={label}
        aria-disabled={disabled || undefined}
        onClick={() => {
          if (!disabled) onCompact();
        }}
      >
        <CircularProgress aria-label="Context used" value={pct === null ? 0 : Math.min(100, pct) / 100} size={20} thickness={3} tone={ringTone(usage)} />
        <span className={styles.ringText} aria-hidden="true">
          {text}
        </span>
      </button>
    </Tooltip>
  );
}

/* ───────────────────────── primary button (IA §6) ───────────────────────── */

const PRIMARY_ICON = { voice: 'graphic_eq', send: 'arrow_upward', 'send-disabled': 'arrow_upward', stop: 'stop' } as const;

export function PrimaryButton({ mode, label, onPress }: { readonly mode: PrimaryMode; readonly label?: string; readonly onPress: (mode: PrimaryMode) => void }) {
  const disabled = mode === 'send-disabled';
  return (
    <button
      type="button"
      className={cx(styles.primary, 'has-state-layer')}
      data-mode={mode}
      aria-label={label ?? PRIMARY_LABEL[mode]}
      aria-disabled={disabled || undefined}
      onClick={() => {
        if (!disabled) onPress(mode);
      }}
    >
      {mode === 'stop' ? <Icon name="stop" filled size={24} /> : <Icon name={PRIMARY_ICON[mode]} size={24} />}
    </button>
  );
}

/* ───────────────────────── queue tray (I-12, uploads) ───────────────────────── */

export interface TrayItem {
  readonly key: string;
  readonly kind: 'queued' | 'upload' | 'error';
  readonly text: string;
  readonly detail?: string;
  /** 0–1 for uploads with a known size. */
  readonly fraction?: number | null;
  readonly onCancel?: () => void;
  readonly cancelLabel?: string;
}

/** Chips above the composer: queued prompts ("Queued · …") and uploads in progress. */
export function QueueTray({ items }: { readonly items: readonly TrayItem[] }) {
  if (items.length === 0) return null;
  return (
    <ul className={styles.tray} aria-label="Waiting to send">
      {items.map((it) => (
        <li key={it.key} className={styles.trayChip} data-kind={it.kind}>
          {it.kind === 'upload' ? (
            it.fraction === null || it.fraction === undefined ? (
              <Spinner size={14} />
            ) : (
              <CircularProgress aria-label="Upload progress" value={it.fraction} size={16} thickness={2} />
            )
          ) : (
            <Icon name={it.kind === 'error' ? 'error' : 'hourglass_top'} size={16} />
          )}
          <span className={styles.trayLead}>{it.detail ?? (it.kind === 'queued' ? 'Queued' : 'Uploading')}</span>
          <span className={styles.trayText} title={it.text}>
            {it.text}
          </span>
          {it.onCancel ? <IconButton icon="close" size="small" iconSize={16} aria-label={it.cancelLabel ?? 'Remove'} onClick={it.onCancel} className={styles.trayX} /> : null}
        </li>
      ))}
    </ul>
  );
}

/* ───────────────────────── recording strip (inv02 F-17) ───────────────────────── */

export function RecordingStrip({ startedAt, processing, onCancel }: { readonly startedAt: number; readonly processing: boolean; readonly onCancel: () => void }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (processing) return undefined;
    const t = setInterval(() => {
      setNow(Date.now());
    }, 250);
    return () => {
      clearInterval(t);
    };
  }, [processing]);
  return (
    <div className={styles.recording} role="status" aria-live="polite">
      {processing ? <Spinner size={16} /> : <span className={styles.recDot} aria-hidden="true" />}
      <span className={styles.recText}>{processing ? 'Preparing voice message…' : `Recording ${formatClock(now - startedAt)}`}</span>
      <span className={styles.recHint}>{processing ? '' : 'max 1:00'}</span>
      <IconButton icon="close" aria-label="Discard voice message" onClick={onCancel} disabled={processing} />
    </div>
  );
}

/* ───────────────────────── read-only bar (spec 12 H-3) ───────────────────────── */

export function ReadOnlyBar({ archie, onResume }: { readonly archie: boolean; readonly onResume: () => void }) {
  return (
    <div className={styles.readOnly} role="region" aria-label="Read-only conversation">
      <Icon name="visibility" size={20} />
      <span className={styles.readOnlyText}>
        {archie ? 'A past Archie conversation, read only.' : 'A past conversation, read only.'}
      </span>
      <Button variant="tonal" icon="play_arrow" onClick={onResume}>
        Resume
      </Button>
    </div>
  );
}

/* ───────────────────────── slash commands (GET /api/skills) ───────────────────────── */

function SkillList({ onPick }: { readonly onPick: (name: string) => void }) {
  const [skills, setSkills] = useState<readonly SkillInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    api.catalogs
      .skills()
      .then((r) => {
        if (live) setSkills(Array.isArray(r.skills) ? r.skills : []);
      })
      .catch((err: unknown) => {
        if (live) setError(errorMessage(err));
      });
    return () => {
      live = false;
    };
  }, []);
  if (error) return <p className={styles.dialogNote}>{error}</p>;
  if (skills === null)
    return (
      <div className={styles.dialogNote}>
        <Spinner size={20} /> Loading…
      </div>
    );
  if (skills.length === 0) return <p className={styles.dialogNote}>No skills on this server.</p>;
  return (
    <List aria-label="Skills">
      {skills.map((s) => (
        <ListItem
          key={s.name}
          headline={`/${s.name}`}
          supporting={s.description}
          leading="terminal"
          onClick={() => {
            onPick(s.name);
          }}
        />
      ))}
    </List>
  );
}

/** Skills from `GET /api/skills`; picking one puts `/name ` at the start of the draft. */
export function SlashCommandsDialog({ open, onClose, onPick }: { readonly open: boolean; readonly onClose: () => void; readonly onPick: (name: string) => void }) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Slash commands"
      icon="terminal"
      maxWidth={480}
      fullScreen="compact"
      actions={
        <Button variant="text" onClick={onClose}>
          Close
        </Button>
      }
    >
      {open ? <SkillList onPick={onPick} /> : null}
    </Dialog>
  );
}
