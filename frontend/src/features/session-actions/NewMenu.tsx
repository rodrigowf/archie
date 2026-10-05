/**
 * `NewMenu` — the items of the "＋ New" menu (rail FAB, drawer): New Archie conversation
 * (Ctrl+Alt+N) and New agent session (Ctrl+Alt+Shift+N). "New Archie" goes through spec 12
 * §6.11: with Archie running anywhere it asks first (Open the running one / Stop it and start
 * new / Cancel), fixing "New Archie just focuses the open one" (inv02 F-25).
 */
import type { ReactNode } from 'react';
import { MenuItem } from '@/ui/overlays';
import { requestOpenArchie } from './actions';

export interface NewMenuProps {
  /** Leading mark for the Archie item (the app's Archie mark). */
  readonly archieMark?: ReactNode;
  /** Runs first (the shell closes its overlays and returns to the workspace). */
  readonly prepare?: () => void;
  /** New agent session (the shell's flow). */
  readonly onNewAgent: () => void;
}

/** The shell-independent "New Archie conversation" action (keyboard, empty workspace, switcher). */
export function requestNewArchie(prepare?: () => void): void {
  prepare?.();
  void requestOpenArchie({ mode: 'new' });
}

export function NewMenu({ archieMark, prepare, onNewAgent }: NewMenuProps) {
  return (
    <>
      <MenuItem
        {...(archieMark ? { leading: archieMark } : { icon: 'forum' as const })}
        shortcut="Ctrl+Alt+N"
        onSelect={() => {
          requestNewArchie(prepare);
        }}
      >
        New Archie conversation
      </MenuItem>
      <MenuItem
        icon="terminal"
        shortcut="Ctrl+Alt+Shift+N"
        onSelect={() => {
          prepare?.();
          onNewAgent();
        }}
      >
        New agent session
      </MenuItem>
    </>
  );
}
