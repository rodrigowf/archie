/**
 * The Agents button in the composer row, next to the context ring (PLAN §4.4 item 2). Shown only
 * while the session has at least one subagent (IA §6 keeps the zero state clean); the number is the
 * running count. Opens the agents sheet (a lazy chunk).
 */
import { lazy, memo, Suspense, useRef, useState } from 'react';
import { runningSubagents } from '@/protocol';
import { useSession, useShallow } from '@/stores';
import { Tooltip } from '@/ui/overlays';
import { Icon, cx } from '@/ui/primitives';
import styles from './Subagents.module.css';

const LazySheet = lazy(() => import('./AgentsSheet'));

/** Start fetching the sheet chunk (hover / focus of the button). */
export function preloadAgentsSheet(): void {
  void import('./AgentsSheet');
}

function AgentsButtonImpl({ localId }: { localId: string }) {
  const { total, running } = useSession(
    localId,
    useShallow((s) => ({ total: s.conv.subagents.length, running: runningSubagents(s.conv) })),
  );
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLButtonElement | null>(null);
  if (total === 0) return null;
  const label = `Agents: ${running} running, ${total} in total`;
  return (
    <>
      <Tooltip label={running > 0 ? `${running} agent${running === 1 ? '' : 's'} running` : 'Agents'} inline>
        <button
          ref={ref}
          type="button"
          className={cx(styles.agentsButton, 'has-state-layer')}
          data-agents-button=""
          data-running={running > 0 ? '' : undefined}
          aria-label={label}
          aria-haspopup="dialog"
          aria-expanded={open}
          onMouseEnter={preloadAgentsSheet}
          onFocus={preloadAgentsSheet}
          onClick={() => {
            setOpen(true);
          }}
        >
          <Icon name="account_tree" size={20} />
          {running > 0 ? (
            <span className={styles.agentsCount} aria-hidden="true">
              {running}
            </span>
          ) : null}
        </button>
      </Tooltip>
      {open ? (
        <Suspense fallback={null}>
          <LazySheet
            localId={localId}
            open
            onClose={() => {
              setOpen(false);
              ref.current?.focus();
            }}
          />
        </Suspense>
      ) : null}
    </>
  );
}

export const AgentsButton = memo(AgentsButtonImpl);
