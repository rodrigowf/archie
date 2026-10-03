/**
 * PLACEHOLDER (W-11 `SessionMenu{localId}`): the ⋮ session menu. Rename and Close are wired to
 * the shell's flows (W-07 owns tab rename and close-with-confirm); the other items show the
 * approved structure (IA §3: Rename, Session settings, Compact context, Fork, Close, Delete) and
 * stay disabled until W-11.
 */
import { useRef, useState } from 'react';
import { findTab } from '@/stores';
import { IconButton } from '@/ui/controls';
import { Menu, MenuItem, MenuSeparator } from '@/ui/overlays';
import { requestCloseTab, requestRename } from '../shell/actions';

export function SessionMenu({ localId }: { localId: string }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLButtonElement>(null);
  const tab = findTab(localId);
  const chat = !!tab && (tab.kind === 'archie' || tab.kind === 'agent');
  return (
    <>
      <IconButton
        ref={ref}
        icon="more_vert"
        aria-label={chat ? 'Session menu' : 'Tab menu'}
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
        aria-label={chat ? 'Session menu' : 'Tab menu'}
      >
        {chat ? (
          <>
            <MenuItem
              icon="edit"
              shortcut="F2"
              disabled={!!tab.readOnly}
              onSelect={() => {
                requestRename(localId);
              }}
            >
              Rename
            </MenuItem>
            <MenuItem icon="tune" disabled description="W-11">
              Session settings
            </MenuItem>
            <MenuItem icon="compress" disabled description="W-11">
              Compact context
            </MenuItem>
            <MenuItem icon="call_split" disabled description="W-11">
              Fork
            </MenuItem>
            <MenuSeparator />
          </>
        ) : null}
        <MenuItem
          icon="close"
          shortcut="Ctrl+Alt+W"
          onSelect={() => {
            requestCloseTab(localId);
          }}
        >
          Close
        </MenuItem>
        {chat ? (
          <MenuItem icon="delete" destructive disabled description="W-11">
            Delete
          </MenuItem>
        ) : null}
      </Menu>
    </>
  );
}
