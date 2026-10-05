/**
 * `Composer{localId}` (W-11; IA §6; mockups "Composer states", phone (i)): one 28 dp container
 * with ＋ (attach file · voice message · slash commands), a multiline 16 px field, the context
 * ring (tap → Compact), the 🎙 voice-message button (Archie, P-5) and the morphing primary
 * button Voice → Send → Stop.
 *
 * - Enter sends, Shift+Enter is a newline; **Enter while working sends a queued message**
 *   (inv02 F-16): agent prompts wait in the tray (I-12) as "Queued" chips above the field.
 * - Typing while a permission is pending answers it: the send denies it with the text as
 *   feedback (spec 12 §6.9; the server resolves it).
 * - The draft is per session and survives tab switches (panels stay mounted) and reloads
 *   (`draft:<local_id>` in sessionStorage, W-06 store).
 * - The field is the only text input here and uses the 16 px body-large size at every width
 *   (iOS focus zoom; fixes inv02 §6.2). On iOS the page offset is undone on blur (spec 13 §2.6).
 * - Read-only views (H-3) show a Resume bar instead.
 */
import { memo, useCallback, useEffect, useLayoutEffect, useRef, useState, type ChangeEvent, type KeyboardEvent } from 'react';
import { contextUsage, isBusy } from '@/protocol';
import { ArchieRuntime, errorMessage, getSessionRuntime, sharedFileText, uploadFile, AbortedError } from '@/services';
import { generateUUID } from '@/platform';
import {
  getSessionEntry,
  modelAcceptsAudio,
  showSnackbar,
  useCapabilities,
  useSession,
  useShallow,
  useTabLiveStatus,
  useTabTitle,
} from '@/stores';
import { compactSession, resumeReadOnly, STOP_FIRST } from '@/features/session-actions';
import { IconButton } from '@/ui/controls';
import { Menu, MenuItem } from '@/ui/overlays';
import { cx } from '@/ui/primitives';
import { micErrorMessage, startRecording, type Recording } from './audio/recorder';
import { isSubmitKey, placeholderFor, primaryMode, withSlashCommand, type PrimaryMode } from './logic';
import { ContextRing, PrimaryButton, QueueTray, ReadOnlyBar, RecordingStrip, SlashCommandsDialog, type TrayItem } from './parts';
import { startVoice } from './voiceHook';
import styles from './Composer.module.css';

export interface ComposerProps {
  readonly localId: string;
}

/** The attribute the shell's `/` shortcut focuses (W-07 `COMPOSER_FIELD_ATTR`). */
export const COMPOSER_FIELD_ATTR = 'data-composer-field';

const GENERIC_AGENT_TITLE = /^(new session|untitled)?$/i;

interface UploadItem {
  readonly id: string;
  readonly name: string;
  readonly fraction: number | null;
  readonly abort: (() => void) | null;
}

interface RecState {
  readonly rec: Recording;
  readonly processing: boolean;
}

function isIOS(): boolean {
  const nav = navigator as Navigator & { maxTouchPoints?: number };
  return /iP(hone|ad|od)/.test(nav.userAgent) || (nav.platform === 'MacIntel' && (nav.maxTouchPoints ?? 0) > 1);
}

/** Recording works through MediaRecorder or the WAV fallback (ScriptProcessor + Web Audio). */
function canRecord(c: { getUserMedia: boolean; mediaRecorder: boolean; webAudio: boolean } | null): boolean {
  return !!c && c.getUserMedia && (c.mediaRecorder || c.webAudio);
}

function ComposerImpl({ localId }: ComposerProps) {
  const s = useSession(
    localId,
    useShallow((x) => ({
      kind: x.conv.ref.kind,
      readOnly: x.readOnly,
      draft: x.draft,
      working: isBusy(x.conv) || x.conv.inTurn,
      queue: x.conv.queue,
      percent: contextUsage(x.conv).percent,
      level: contextUsage(x.conv).level,
      hasEntries: x.conv.entries.length > 0,
      voiceActive: x.conv.voiceActive,
      modelInfo: x.modelInfo,
      subscribed: x.conv.conn === 'subscribed',
    })),
  );
  const live = useTabLiveStatus(localId);
  const title = useTabTitle(localId);
  const client = useCapabilities((c) => c.client);
  const audioModel = useCapabilities((c) => modelAcceptsAudio(s.modelInfo, c));
  const field = useRef<HTMLTextAreaElement>(null);
  const plusRef = useRef<HTMLButtonElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const [plusOpen, setPlusOpen] = useState(false);
  const [slashOpen, setSlashOpen] = useState(false);
  const [uploads, setUploads] = useState<readonly UploadItem[]>([]);
  const [rec, setRecState] = useState<RecState | null>(null);
  const recRef = useRef<RecState | null>(null);
  const setRec = useCallback((v: RecState | null): void => {
    recRef.current = v;
    setRecState(v);
  }, []);

  const archie = s.kind === 'orchestrator';
  const hasText = s.draft.trim().length > 0;
  const permissionPending = live?.permissionPending === true;
  // P-5: only when the CURRENT Archie model accepts audio (fixes inv02 §6.2 "any model").
  const micAvailable = archie && !s.readOnly && audioModel && canRecord(client);
  const voiceAvailable =
    archie && !s.voiceActive && !!client && client.secureContext && client.getUserMedia && (client.webrtc || client.webAudio);
  const mode: PrimaryMode = rec ? 'send' : primaryMode({ kind: s.kind, hasText, working: s.working, voiceAvailable });

  const setDraft = useCallback(
    (text: string): void => {
      getSessionEntry(localId)?.handle.setDraft(text);
    },
    [localId],
  );

  // auto-grow up to the CSS max-height (inv02 F-16: 200 px)
  useLayoutEffect(() => {
    const el = field.current;
    if (!el) return;
    el.style.height = 'auto';
    // a hidden panel (display: none) measures 0: keep the CSS height until it is shown again
    if (el.scrollHeight > 0) el.style.height = `${el.scrollHeight}px`;
  }, [s.draft, rec]);

  // a recording never outlives its composer (tab closed)
  useEffect(
    () => () => {
      recRef.current?.rec.cancel();
    },
    [],
  );

  // ───────── actions ─────────

  const send = (): void => {
    const text = s.draft.trim();
    if (!text) return;
    const rt = getSessionRuntime(localId);
    if (!rt || rt.readOnly) return;
    rt.send(text); // agent: tray when busy (I-12); a pending permission → deny with feedback (§6.9)
    setDraft('');
  };

  const stop = (): void => {
    const rt = getSessionRuntime(localId);
    if (!rt || rt.readOnly) return;
    // §6.3: interrupt drops the server queue; our queued texts come back into the field
    const mine = rt.kind === 'agent' ? rt.conv.queue.filter((q) => q.owner === 'local').map((q) => q.text) : [];
    rt.interrupt();
    if (mine.length && !s.draft.trim()) setDraft(mine.join('\n\n'));
  };

  const finishRecording = async (): Promise<void> => {
    const cur = recRef.current;
    if (!cur || cur.processing) return;
    setRec({ rec: cur.rec, processing: true });
    try {
      const msg = await cur.rec.stop();
      const rt = getSessionRuntime(localId);
      if (rt instanceof ArchieRuntime && !rt.readOnly) {
        const note = (getSessionEntry(localId)?.handle.store.getState().draft ?? '').trim();
        rt.sendAudio(msg.base64, msg.format, note || undefined); // §6.16, orchestrator WS only
        if (note) setDraft('');
      }
    } catch (err) {
      showSnackbar(micErrorMessage(err), { tone: 'error' });
    } finally {
      setRec(null);
    }
  };

  const startRec = (): void => {
    if (recRef.current) return;
    if (s.working) {
      showSnackbar(STOP_FIRST);
      return;
    }
    startRecording({ preferMediaRecorder: !!client?.mediaRecorder, onLimit: () => void finishRecording() }).then(
      (r) => {
        setRec({ rec: r, processing: false });
      },
      (err: unknown) => {
        showSnackbar(micErrorMessage(err), { tone: 'error' });
      },
    );
  };

  const cancelRec = (): void => {
    recRef.current?.rec.cancel();
    setRec(null);
  };

  const onPrimary = (m: PrimaryMode): void => {
    if (rec) {
      void finishRecording();
      return;
    }
    if (m === 'send') send();
    else if (m === 'stop') stop();
    else if (m === 'voice') startVoice(localId);
  };

  const upload = (file: File): void => {
    const id = generateUUID();
    // AbortController arrived in Safari 12.1: feature-detected, the upload just can't be cancelled without it
    const AC = (window as { AbortController?: typeof AbortController }).AbortController;
    const ctrl = typeof AC === 'function' ? new AC() : null;
    setUploads((u) => u.concat([{ id, name: file.name || 'file', fraction: 0, abort: ctrl ? () => ctrl.abort() : null }]));
    const drop = (): void => {
      setUploads((u) => u.filter((x) => x.id !== id));
    };
    uploadFile(file, {
      ...(ctrl ? { signal: ctrl.signal } : {}),
      onProgress: (p) => {
        setUploads((u) => u.map((x) => (x.id === id ? { ...x, fraction: p.fraction } : x)));
      },
    }).then(
      (r) => {
        drop();
        const line = sharedFileText(r);
        const rt = getSessionRuntime(localId);
        if (rt instanceof ArchieRuntime) {
          rt.inject(line); // §6.15: Archie gets the file as a shared-file line
        } else {
          const cur = getSessionEntry(localId)?.handle.store.getState().draft ?? '';
          setDraft(cur.trim() ? `${cur.replace(/\s+$/, '')}\n${line}` : line); // the reference line goes into the draft
          field.current?.focus();
        }
      },
      (err: unknown) => {
        drop();
        if (err instanceof AbortedError) return;
        showSnackbar(`${file.name || 'Upload'}: ${errorMessage(err)}`, { tone: 'error' }); // G-1: 413 → "File too large for the server…"
      },
    );
  };

  const onFile = (e: ChangeEvent<HTMLInputElement>): void => {
    const files = e.target.files ? Array.from(e.target.files) : [];
    e.target.value = '';
    for (const f of files) upload(f);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>): void => {
    if (!isSubmitKey({ key: e.key, shiftKey: e.shiftKey, isComposing: e.nativeEvent.isComposing, keyCode: e.keyCode })) return;
    e.preventDefault();
    send();
  };

  // ───────── render ─────────

  const tray: TrayItem[] = s.queue.map((q, i) => ({
    key: `q${i}:${q.text}`,
    kind: 'queued' as const,
    text: q.text,
    detail: q.owner === 'remote' ? 'Queued · other device' : 'Queued',
  }));
  for (const u of uploads)
    tray.push({
      key: u.id,
      kind: 'upload',
      text: u.name,
      detail: u.fraction === null ? 'Uploading' : `Uploading ${Math.round(u.fraction * 100)}%`,
      fraction: u.fraction,
      ...(u.abort ? { onCancel: u.abort, cancelLabel: `Cancel upload of ${u.name}` } : {}),
    });

  if (s.readOnly) {
    return (
      <div className={styles.wrap}>
        <ReadOnlyBar archie={archie} onResume={() => void resumeReadOnly(localId)} />
      </div>
    );
  }

  const showRing = !rec && (s.percent !== null || s.hasEntries);
  const agentTitle = GENERIC_AGENT_TITLE.test(title.trim()) ? '' : title;
  const placeholder = placeholderFor({ kind: s.kind, title: agentTitle, working: s.working, permissionPending });

  return (
    <div className={styles.wrap} data-composer="">
      <QueueTray items={tray} />
      <div className={cx(styles.cmp, rec && styles.cmpRecording)}>
        <IconButton
          ref={plusRef}
          icon="add"
          aria-label="Attach, voice message or slash command"
          aria-haspopup="menu"
          aria-expanded={plusOpen}
          disabled={!!rec}
          onClick={() => {
            setPlusOpen((o) => !o);
          }}
        />
        <Menu
          open={plusOpen}
          onClose={() => {
            setPlusOpen(false);
          }}
          anchor={plusRef}
          placement="top-start"
          minWidth={220}
          aria-label="Add to message"
        >
          <MenuItem icon="attach_file" onSelect={() => fileRef.current?.click()}>
            Attach file
          </MenuItem>
          {micAvailable ? (
            <MenuItem icon="mic" disabled={s.working} description={s.working ? STOP_FIRST : undefined} onSelect={startRec}>
              Voice message
            </MenuItem>
          ) : null}
          {archie ? null : (
            <MenuItem
              icon="terminal"
              onSelect={() => {
                setSlashOpen(true);
              }}
            >
              Slash commands
            </MenuItem>
          )}
        </Menu>
        {rec ? (
          <RecordingStrip startedAt={rec.rec.startedAt} processing={rec.processing} onCancel={cancelRec} />
        ) : (
          <textarea
            ref={field}
            className={styles.field}
            rows={1}
            value={s.draft}
            placeholder={placeholder}
            aria-label="Message"
            {...{ [COMPOSER_FIELD_ATTR]: '' }}
            enterKeyHint="send"
            onChange={(e) => {
              setDraft(e.target.value);
            }}
            onKeyDown={onKeyDown}
            onBlur={() => {
              if (isIOS()) window.scrollTo(0, 0); // iOS 12 has no visualViewport (spec 13 §2.6)
            }}
          />
        )}
        {showRing ? (
          <ContextRing
            usage={{ percent: s.percent, level: s.level }}
            disabled={s.working || !s.subscribed}
            disabledReason={s.working ? STOP_FIRST : 'Not connected'}
            onCompact={() => {
              compactSession(localId);
            }}
          />
        ) : null}
        {micAvailable && !rec ? (
          <IconButton icon="mic" aria-label="Record voice message" disabled={s.working} onClick={startRec} />
        ) : null}
        <PrimaryButton mode={mode} label={rec ? 'Send voice message' : undefined} onPress={onPrimary} />
        <input ref={fileRef} type="file" multiple hidden tabIndex={-1} aria-hidden="true" onChange={onFile} />
      </div>
      {archie ? null : (
        <SlashCommandsDialog
          open={slashOpen}
          onClose={() => {
            setSlashOpen(false);
          }}
          onPick={(name) => {
            setSlashOpen(false);
            setDraft(withSlashCommand(getSessionEntry(localId)?.handle.store.getState().draft ?? '', name));
            field.current?.focus();
          }}
        />
      )}
    </div>
  );
}

export const Composer = memo(ComposerImpl);
