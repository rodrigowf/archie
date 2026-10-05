/**
 * `SessionMenu{localId}` — the ⋮ session menu (IA §3, mockups "Menu and dialog"): Rename F2,
 * Session settings, Compact context, Fork, — Close Ctrl+Alt+W, Delete. Unavailable items stay
 * visible and say why (ID-3, fixes inv02 §6.2 "rename, rewind, fork and session config before the
 * first turn silently do nothing"). Document tabs (memory, visual) only offer Close.
 *
 * Rename and Close run the shell's flows (W-07 owns the rename dialog and close-with-confirm);
 * Session settings opens W-13's sheet. They arrive as props, wired in the app's slot registry.
 */
import { useRef, useState } from 'react';
import { useTabs } from '@/stores';
import { IconButton } from '@/ui/controls';
import { Menu, MenuItem, MenuSeparator } from '@/ui/overlays';
import { useSessionActions, type ActionState } from './useSessionActions';

export interface SessionMenuProps {
  readonly localId: string;
  /** Open the rename dialog (W-07 `requestRename`). Hidden when absent. */
  readonly onRename?: (localId: string) => void;
  /** Close with the shell's confirmation (W-07 `requestCloseTab`). */
  readonly onClose?: (localId: string) => void;
  /** Open the session settings sheet (W-13). The item says "Coming soon" until it exists. */
  readonly onSessionSettings?: (localId: string) => void;
}

function why(a: ActionState): string | undefined {
  return a.enabled ? undefined : (a.reason ?? undefined);
}

export function SessionMenu({ localId, onRename, onClose, onSessionSettings }: SessionMenuProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLButtonElement>(null);
  const tabKind = useTabs((s) => s.tabs.find((t) => t.id === localId)?.kind);
  const a = useSessionActions(localId);
  const chat = tabKind === 'archie' || tabKind === 'agent';
  const label = chat ? 'Session menu' : 'Tab menu';
  const settings: ActionState = onSessionSettings ? a.settings : { enabled: false, reason: 'Coming soon' };
  const pct = a.contextPercent;
  return (
    <>
      <IconButton
        ref={ref}
        icon="more_vert"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => {
          setOpen((o) => !o);
        }}
      />
      <Menu
        open={open}
        onClose={() => {
          setOpen(false);
        }}
        anchor={ref}
        placement="bottom-end"
        minWidth={220}
        aria-label={label}
      >
        {chat ? (
          <>
            {onRename ? (
              <MenuItem icon="edit" shortcut="F2" disabled={!a.rename.enabled} description={why(a.rename)} onSelect={() => onRename(localId)}>
                Rename
              </MenuItem>
            ) : null}
            {tabKind === 'agent' ? (
              <MenuItem
                icon="tune"
                disabled={!settings.enabled}
                description={why(settings)}
                onSelect={() => onSessionSettings?.(localId)}
              >
                Session settings
              </MenuItem>
            ) : null}
            <MenuItem
              icon="compress"
              disabled={!a.compact.enabled}
              description={why(a.compact) ?? (pct === null ? undefined : `${pct}% of the context used`)}
              onSelect={() => {
                a.compactNow();
              }}
            >
              Compact context
            </MenuItem>
            <MenuItem icon="call_split" disabled={!a.fork.enabled} description={why(a.fork)} onSelect={a.requestFork}>
              Fork
            </MenuItem>
            <MenuSeparator />
          </>
        ) : null}
        {onClose ? (
          <MenuItem icon="close" shortcut="Ctrl+Alt+W" onSelect={() => onClose(localId)}>
            Close
          </MenuItem>
        ) : null}
        {chat ? (
          <MenuItem icon="delete" destructive disabled={!a.del.enabled} description={why(a.del)} onSelect={a.requestDelete}>
            Delete
          </MenuItem>
        ) : null}
      </Menu>
    </>
  );
}
