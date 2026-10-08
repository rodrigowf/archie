/**
 * History list (spec 13 §3.6 `features/history`, IA §3 / §5, mockups desktop list pane and
 * phone (b) drawer): search (client-side title filter), **Open now** (the open conversations with
 * their live status), then past conversations in date groups Today / Yesterday / Previous 7 days /
 * Earlier with a time stamp, and rename / duplicate / delete per row.
 *
 * inv02 F-19 behaviour kept: titles come from `GET /api/sessions` (renames are optimistic, 404
 * tolerated, then a refresh); Duplicate does not open the copy (a snackbar offers it); Delete asks
 * first and moves the conversation to the server's trash. Fixed: search and date grouping were
 * missing (inv02 §6.1); a failed list fetch keeps the previous rows.
 *
 * The shell (W-07) owns tabs and live status, so the Open now rows arrive as props
 * (`openNow`), with their leading icon and status indicator already rendered: the list shows
 * exactly what the tab strip shows. Opening a row goes back through the shell (`onOpenSession`).
 */
import { useMemo, useState, type ReactNode } from 'react';
import { deleteSession, duplicateSession, errorMessage, refreshSessionList, renameSession, type SessionInfo } from '@/services';
import { patchSessionActions } from '@/features/session-actions';
import { showSnackbar, useCatalog, useProviderLabel } from '@/stores';
import { SearchField, Tag } from '@/ui/controls';
import { NavigationDrawerHeadline, NavigationDrawerItem } from '@/ui/navigation';
import { ConfirmDialog } from '@/ui/overlays';
import { Icon } from '@/ui/primitives';
import { ActionRow, type RowAction } from './ActionRow';
import { groupSessions, matchesQuery, sessionTitleOf } from './grouping';
import { RenameDialog } from './RenameDialog';
import { historyStamp, parseServerTime } from './time';
import { useMinuteClock } from './useMinuteClock';
import styles from './history.module.css';

/** One open conversation, as the shell presents it. */
export interface OpenNowItem {
  /** Tab id (`local_id`). */
  readonly id: string;
  readonly title: string;
  readonly sdkId: string | null;
  readonly isArchie: boolean;
  /** "Claude" / "Qwen" / "Gemini" for agent sessions. */
  readonly providerLabel: string | null;
  /** "Ready", "Thinking…", "Waiting for approval"… */
  readonly statusLabel: string | null;
  /** Leading icon (Archie mark or kind icon, with the unseen badge). */
  readonly leading: ReactNode;
  /** The status indicator (spinner / dot / hand / warning). */
  readonly status: ReactNode;
}

export type HistoryVariant = 'pane' | 'drawer' | 'switcher';

export interface HistoryPaneProps {
  /** pane: list pane / History screen (two-line Open now rows); drawer: compact drawer body; switcher: history only. */
  readonly variant?: HistoryVariant;
  readonly openNow?: readonly OpenNowItem[];
  readonly activeId?: string | null;
  readonly onFocusOpen?: (id: string) => void;
  /** Open a past conversation (W-07 `openFromHistory`: resume, or the Archie conflict flow). */
  readonly onOpenSession?: (session: SessionInfo) => void;
  /** Leading mark for Archie rows (the shell's ArchieMark). */
  readonly archieMark?: ReactNode;
  /** Fixed clock (tests, gallery). */
  readonly now?: number;
}

type Pending = { kind: 'rename' | 'delete'; session: SessionInfo } | null;

async function withBusy<T>(label: string, fn: () => Promise<T>): Promise<T> {
  patchSessionActions({ busy: label });
  try {
    return await fn();
  } finally {
    patchSessionActions({ busy: null });
  }
}

export function HistoryPane({
  variant = 'pane',
  openNow = [],
  activeId = null,
  onFocusOpen,
  onOpenSession,
  archieMark,
  now: fixedNow,
}: HistoryPaneProps) {
  const [query, setQuery] = useState('');
  const [pending, setPending] = useState<Pending>(null);
  const sessions = useCatalog((s) => s.sessions);
  const providerLabelOf = useProviderLabel();
  const now = useMinuteClock(fixedNow);

  const showOpen = variant !== 'switcher';
  const openShown = showOpen ? openNow.filter((o) => matchesQuery(o.title, query)) : [];

  const sections = useMemo(() => {
    const openSdk = new Set<string>();
    const openIds = new Set<string>();
    if (showOpen)
      for (const o of openNow) {
        openIds.add(o.id);
        if (o.sdkId) openSdk.add(o.sdkId);
      }
    return groupSessions(sessions.items, {
      query,
      now,
      exclude: (s) => openSdk.has(s.session_id) || (!!s.local_id && openIds.has(s.local_id)),
    });
  }, [sessions.items, openNow, query, now, showOpen]);

  const rowActions = (s: SessionInfo): RowAction[] => {
    return [
      {
        id: 'rename',
        icon: 'edit',
        label: 'Rename',
        onSelect: () => {
          setPending({ kind: 'rename', session: s });
        },
      },
      {
        id: 'duplicate',
        icon: 'content_copy',
        label: 'Duplicate',
        onSelect: () => {
          void duplicate(s);
        },
      },
      {
        id: 'delete',
        icon: 'delete',
        label: 'Delete',
        destructive: true,
        onSelect: () => {
          setPending({ kind: 'delete', session: s });
        },
      },
    ];
  };

  const duplicate = async (s: SessionInfo): Promise<void> => {
    const title = sessionTitleOf(s);
    try {
      const id = await withBusy('Duplicating…', () => duplicateSession(s.session_id));
      showSnackbar(`Duplicated “${title}”`, {
        action: onOpenSession ? { label: 'Open', run: () => onOpenSession({ ...s, session_id: id, local_id: null }) } : undefined,
      });
    } catch (err) {
      showSnackbar(errorMessage(err), { tone: 'error' });
    }
  };

  const commitRename = async (s: SessionInfo, t: string): Promise<boolean> => {
    try {
      await renameSession(s.session_id, t);
      return true;
    } catch (err) {
      showSnackbar(errorMessage(err), { tone: 'error' });
      return false;
    }
  };

  const confirmDelete = (s: SessionInfo): void => {
    const title = sessionTitleOf(s);
    setPending(null);
    void (async () => {
      try {
        await withBusy('Deleting…', () => deleteSession(s.session_id));
        showSnackbar(`Deleted “${title}”`);
      } catch (err) {
        showSnackbar(errorMessage(err), { tone: 'error' });
      }
      void refreshSessionList();
    })();
  };

  const twoLine = variant === 'pane';
  const empty = !sessions.loading && sessions.loadedAt > 0 && sessions.items.length === 0 && openNow.length === 0;
  const noMatch = !!query.trim() && openShown.length === 0 && sections.length === 0 && !empty;

  return (
    <div className={variant === 'pane' ? styles.history : `${styles.history} ${styles.historyDrawer}`} data-variant={variant}>
      <SearchField label="Search conversations" value={query} onValueChange={setQuery} className={styles.search} data-app-search="" />
      {openShown.length ? (
        <div role="group" aria-label="Open now">
          <NavigationDrawerHeadline trailing={String(openShown.length)}>Open now</NavigationDrawerHeadline>
          {openShown.map((o) => (
            <NavigationDrawerItem
              key={o.id}
              leading={o.leading}
              label={o.title}
              supporting={
                twoLine ? (
                  <>
                    {o.isArchie ? <span>Archie</span> : o.providerLabel ? <Tag>{o.providerLabel}</Tag> : null}
                    {o.statusLabel ? (
                      <span className={o.isArchie || o.providerLabel ? styles.supportText : undefined}>
                        {o.isArchie ? `· ${o.statusLabel}` : o.statusLabel}
                      </span>
                    ) : null}
                  </>
                ) : undefined
              }
              trailing={
                <>
                  {!twoLine && o.providerLabel ? <Tag>{o.providerLabel}</Tag> : null}
                  {o.status}
                </>
              }
              active={o.id === activeId}
              onClick={() => onFocusOpen?.(o.id)}
            />
          ))}
        </div>
      ) : null}
      {sections.map((sec) => (
        <div key={sec.group} role="group" aria-label={sec.group}>
          <NavigationDrawerHeadline>{sec.group}</NavigationDrawerHeadline>
          {sec.items.map((s) => {
            const title = sessionTitleOf(s);
            const provider = s.is_orchestrator ? 'Archie' : (providerLabelOf(s.provider) ?? 'Agent');
            return (
              <ActionRow
                key={s.session_id}
                data-row={s.session_id}
                leading={s.is_orchestrator ? (archieMark ?? <Icon name="forum" size={20} />) : <Icon name="terminal" size={20} />}
                label={title}
                title={`${title} · ${provider} · ${s.message_count} ${s.message_count === 1 ? 'message' : 'messages'}`}
                trailing={historyStamp(parseServerTime(s.last_activity), now)}
                onClick={() => onOpenSession?.(s)}
                actions={rowActions(s)}
              />
            );
          })}
        </div>
      ))}
      {sessions.loading && sessions.loadedAt === 0 ? <p className={styles.emptyLine}>Loading conversations…</p> : null}
      {sessions.error ? (
        <p className={styles.error} role="alert">
          {sessions.error}
        </p>
      ) : null}
      {empty ? <p className={styles.emptyLine}>No conversations yet</p> : null}
      {noMatch ? <p className={styles.emptyLine}>No conversations match “{query.trim()}”</p> : null}
      {pending?.kind === 'rename' ? (
        <RenameDialog
          key={pending.session.session_id}
          title="Rename conversation"
          initial={pending.session.is_orchestrator && sessionTitleOf(pending.session) === 'New conversation' ? '' : pending.session.title}
          onCommit={(t) => commitRename(pending.session, t)}
          onClose={() => {
            setPending(null);
          }}
        />
      ) : null}
      <ConfirmDialog
        open={pending?.kind === 'delete'}
        title="Delete this conversation?"
        confirmLabel="Delete"
        destructive
        onConfirm={() => {
          if (pending) confirmDelete(pending.session);
        }}
        onCancel={() => {
          setPending(null);
        }}
      >
        {pending?.kind === 'delete'
          ? `“${sessionTitleOf(pending.session)}” and its ${pending.session.message_count} ${pending.session.message_count === 1 ? 'message' : 'messages'} move to the server's trash (context/trash/). Memory files are kept.`
          : null}
      </ConfirmDialog>
    </div>
  );
}

