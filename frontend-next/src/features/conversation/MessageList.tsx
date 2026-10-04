/**
 * The message column (spec 13 §3.6, §5.1–§5.2; IA §6; mockups desktop `.conv`, phone `.msgs`):
 * max 840 wide and centred, user bubbles right, assistant prose full width, tool groups and
 * notices in timeline order. Renders the bounded window of `useMessageWindow`, and around it:
 *
 * - top: "Scroll up for older messages" / loading while history exists above (inv02 F-11), and the
 *   orchestrator-history footnote (spec 12 §5.7);
 * - bottom: unmatched tool results (R-9), "Connection lost at …"
 *   (mockups (k); a banner, never an entry, I-15) and the gap "Reload" line (§2.3 `gapPossible`);
 * Queued prompts (I-12) are NOT rendered here: they live only in the composer's tray (W-11) until
 *   dispatched, then appear once in the timeline as an ordinary user entry.
 * - below the scroll area: "Jump to latest" while scrolled up. It sits in its own row, so it never
 *   covers message text (fixes visual audit W6).
 */
import { memo, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { busy, unmatchedResults, type Block, type Conversation, type Entry, type SessionKind } from '@/protocol';
import { getSessionRuntime } from '@/services';
import { usePrefs, useSession, useShallow, type SessionStore } from '@/stores';
import { isLowEnd, formatClockTime } from '@/platform';
import { Button, Chip } from '@/ui/controls';
import { Dialog } from '@/ui/overlays';
import { Icon, ScrollArea, Spinner, cx, type ScrollAreaHandle } from '@/ui/primitives';
import { useRichModule, type RichModule } from './lazyRich';
import { NoticeRow } from './entries/NoticeRow';
import { UserMessage } from './entries/UserMessage';
import { MessageActions } from './MessageActions';
import { WINDOW_CAP_LOW_END, WINDOW_CAP_MAIN } from './messageWindow';
import { useMessageWindow } from './useMessageWindow';
import styles from './Conversation.module.css';

export interface MessageListProps {
  readonly localId: string;
  readonly store: SessionStore;
  readonly hidden: boolean;
  /** Rendered when the conversation has no entries (and nothing is loading). */
  readonly empty?: ReactNode;
  /** Window cap override (tests). Default 200, 80 on low-end devices (spec 13 §5.2). */
  readonly cap?: number;
}

interface RowContext {
  readonly localId: string;
  readonly kind: SessionKind;
  readonly liveId: string | null;
  readonly lastId: string | null;
  readonly stalledToolUseId: string | null;
  readonly grouping: boolean;
  readonly actions: boolean;
  readonly canRewind: boolean;
  readonly isBusy: boolean;
  /** The assistant-run renderer from the lazy rich chunk. */
  readonly Assistant: RichModule['RichAssistantMessage'];
}

const Row = memo(function Row({ entry, ctx, active, onTap }: { entry: Entry; ctx: RowContext; active: boolean; onTap: (id: string) => void }) {
  const actions =
    ctx.actions && entry.kind !== 'notice' && !(entry.kind === 'user' && entry.state === 'pending') ? (
      <MessageActions
        localId={ctx.localId}
        targetId={entry.id}
        isLast={entry.id === ctx.lastId}
        canRewind={ctx.canRewind}
        busy={ctx.isBusy}
        side={entry.kind === 'user' ? 'user' : 'assistant'}
      />
    ) : null;
  let body: ReactNode;
  if (entry.kind === 'user') body = <UserMessage entry={entry} actions={actions} />;
  else if (entry.kind === 'notice') body = <NoticeRow entry={entry} />;
  else
    body = (
      <ctx.Assistant
        entry={entry}
        live={entry.id === ctx.liveId}
        sessionKind={ctx.kind}
        grouping={ctx.grouping}
        stalledToolUseId={ctx.stalledToolUseId}
        actions={actions}
      />
    );
  return (
    // A tap on a message reveals its ⋮ on touch screens (pointer devices show it on hover, F-08).
    // Keyboard users reach the ⋮ by Tab; it shows on focus.
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions -- touch reveal only
    <div
      className={styles.row}
      data-entry-id={entry.id}
      data-kind={entry.kind}
      data-notice={entry.kind === 'notice' ? entry.notice : undefined}
      data-active={active ? '' : undefined}
      onClick={() => onTap(entry.id)}
    >
      {body}
    </div>
  );
});

/** "Connection lost at 21:14:32" with Reconnect (mockups (k)). The time is when this view saw the drop. */
function ConnectionLine({ localId, store }: { localId: string; store: SessionStore }) {
  const lost = useSession(localId, (s) => s.conv.connectionBanner?.code === 'disconnected');
  const readOnly = useSession(localId, (s) => s.readOnly);
  const [since, setSince] = useState<number | null>(() => (lost ? Date.now() : null));
  useEffect(
    () =>
      store.subscribe((s, prev) => {
        const now = s.conv.connectionBanner?.code === 'disconnected';
        if (now && prev.conv.connectionBanner?.code !== 'disconnected') setSince(Date.now());
      }),
    [store],
  );
  if (!lost || since === null) return null;
  const d = new Date(since);
  const pad = (n: number): string => (n < 10 ? `0${n}` : String(n));
  return (
    <div className={styles.sysRow}>
      <span className={styles.sysline} role="status">
        <Icon name="wifi_off" size={16} />
        <span>
          Connection lost at {formatClockTime(since)}:{pad(d.getSeconds())}
        </span>
        {readOnly ? null : (
          <button type="button" className={styles.syslineAction} onClick={() => getSessionRuntime(localId)?.retry()}>
            Reconnect
          </button>
        )}
      </span>
    </div>
  );
}

/** R-9: tool results that could not be matched to a card stay reachable. */
function UnmatchedChip({ localId }: { localId: string }) {
  const { orphanResults, unattributed, hasMore } = useSession(
    localId,
    useShallow((s) => ({ orphanResults: s.conv.orphanResults, unattributed: s.conv.unattributed, hasMore: s.conv.history.hasMore })),
  );
  // unmatchedResults filters into new arrays: compute from the stable slices, not in the selector.
  const un = useMemo(
    () => unmatchedResults({ orphanResults, unattributed, history: { hasMore } } as unknown as Conversation),
    [orphanResults, unattributed, hasMore],
  );
  const [open, setOpen] = useState(false);
  if (un.count === 0) return null;
  const items = [
    ...un.orphans.map((o) => ({ key: `o-${o.tool_use_id}`, id: o.tool_use_id, output: o.output, err: o.is_error })),
    ...un.unattributed.map((u, i) => ({ key: `u-${i}`, id: '', output: u.output, err: u.is_error })),
  ];
  return (
    <div className={styles.sysRow}>
      <Chip icon="info" onClick={() => setOpen(true)}>
        {un.count === 1 ? '1 tool result could not be matched' : `${un.count} tool results could not be matched`}
      </Chip>
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        title="Unmatched tool results"
        actions={
          <Button variant="text" onClick={() => setOpen(false)}>
            Close
          </Button>
        }
      >
        <p className={styles.dialogLead}>These results arrived without a tool call this view could match them to.</p>
        {items.map((it) => (
          <div key={it.key} className={styles.unmatched}>
            <span className={styles.unmatchedHead}>
              {it.id ? `Call ${it.id}` : 'Unknown call'}
              {it.err ? ' · error' : ''}
            </span>
            <pre className={styles.unmatchedOut}>{it.output || '(empty)'}</pre>
          </div>
        ))}
      </Dialog>
    </div>
  );
}

function GapLine({ localId }: { localId: string }) {
  const gap = useSession(localId, (s) => s.conv.gapPossible && !s.conv.reloading);
  if (!gap) return null;
  return (
    <div className={styles.sysRow}>
      <span className={styles.sysline} role="status">
        <Icon name="sync" size={16} />
        <span>Some live activity may be missing.</span>
        <button type="button" className={styles.syslineAction} onClick={() => void getSessionRuntime(localId)?.reload()}>
          Reload
        </button>
      </span>
    </div>
  );
}

function MessageListImpl({ localId, store, hidden, empty, cap }: MessageListProps) {
  const area = useRef<ScrollAreaHandle>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const content = useRef<HTMLDivElement>(null);
  const s = useSession(
    localId,
    useShallow((st) => {
      const c = st.conv;
      const last = c.entries[c.entries.length - 1];
      return {
        kind: c.ref.kind,
        hasSdk: c.ref.sdkId !== null,
        readOnly: st.readOnly,
        isBusy: busy(c.status) || c.inTurn,
        liveId: c.inTurn && last && last.kind === 'assistant' ? last.id : null,
        lastId: last ? last.id : null,
        stalledToolUseId: c.stall && busy(c.status) ? c.stall.last_tool_use_id : null,
        hasMore: c.history.hasMore,
        loadingOlder: st.loadingOlder,
        loading: c.reloading && c.entries.length === 0,
        orchHistory: c.ref.kind === 'orchestrator' && c.history.loaded && c.entries.length > 0 && isHistoryEntry(c.entries[0]),
      };
    }),
  );
  const grouping = usePrefs((p) => p.toolStepGrouping);
  const win = useMessageWindow({
    store,
    area,
    content,
    cap: cap ?? (isLowEnd() ? WINDOW_CAP_LOW_END : WINDOW_CAP_MAIN),
    hidden,
    canLoadOlder: s.hasMore && !s.loadingOlder && s.hasSdk,
    loadOlder: () => void getSessionRuntime(localId)?.loadOlder(),
  });
  const rich = useRichModule();
  const Assistant = rich ? rich.RichAssistantMessage : null;
  const ctx = useMemo<RowContext | null>(
    () =>
      Assistant && {
        Assistant,
        localId,
        kind: s.kind,
        liveId: s.liveId,
        lastId: s.lastId,
        stalledToolUseId: s.stalledToolUseId,
        grouping,
        actions: s.hasSdk,
        canRewind: s.kind === 'agent' && !s.readOnly,
        isBusy: s.isBusy,
      },
    [Assistant, localId, s.kind, s.liveId, s.lastId, s.stalledToolUseId, grouping, s.hasSdk, s.readOnly, s.isBusy],
  );
  const { view } = win;
  const isEmpty = view.entries.length === 0 && view.buffered === 0;
  const above = view.hiddenAbove > 0 || s.hasMore;
  return (
    <div className={styles.listWrap}>
      <ScrollArea ref={area} className={styles.scroll} onScroll={win.onScroll} aria-label="Conversation" tabIndex={0}>
        <div ref={content} className={cx(styles.column, (isEmpty || !ctx) && styles.columnEmpty)}>
          {(s.loading && isEmpty) || (!ctx && !isEmpty) ? (
            <div className={styles.loading} role="status">
              <Spinner size={24} />
              <span>Loading conversation…</span>
            </div>
          ) : isEmpty || !ctx ? (
            empty
          ) : (
            <>
              {above ? (
                <div className={styles.olderHint} role="status">
                  {s.loadingOlder ? <Spinner size={16} /> : <Icon name="keyboard_arrow_up" size={16} />}
                  <span>{s.loadingOlder ? 'Loading older messages…' : 'Scroll up for older messages'}</span>
                </div>
              ) : null}
              {s.orchHistory ? <p className={styles.footnote}>Tool calls and background updates are not kept in orchestrator history.</p> : null}
              {view.entries.map((e) => (
                <Row key={e.id} entry={e} ctx={ctx as RowContext} active={e.id === activeId} onTap={setActiveId} />
              ))}
            </>
          )}
          <UnmatchedChip localId={localId} />
          <GapLine localId={localId} />
          <ConnectionLine localId={localId} store={store} />
        </div>
      </ScrollArea>
      {!win.pinned ? (
        <div className={styles.jumpRow}>
          <Button variant="tonal" size="small" icon="arrow_downward" onClick={win.jumpToLatest}>
            {view.buffered > 0 ? (view.buffered === 1 ? '1 new message' : `${view.buffered} new messages`) : 'Jump to latest'}
          </Button>
        </div>
      ) : null}
    </div>
  );
}

function isHistoryEntry(e: Entry | undefined): boolean {
  if (!e) return false;
  if (e.kind === 'user') return e.origin === 'history';
  if (e.kind === 'assistant') return e.blocks.length > 0 && (e.blocks[0] as Block).origin === 'history';
  return false;
}

export const MessageList = memo(MessageListImpl);
