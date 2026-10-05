/**
 * Compact navigation drawer (IA §5, mockups phone (b)): Archie mark + connection status, search,
 * Open now, history by date (HistoryPane, drawer variant), then Memory / Visuals / Settings in
 * the footer. It replaces the old bottom bar (R1). Modal: scrim, Escape, Back, focus trap.
 */
import { useRef } from 'react';
import { useConnection } from '@/stores';
import { StatusDot } from '@/ui/controls';
import { NavigationDrawer, NavigationDrawerItem } from '@/ui/navigation';
import { HistoryPane } from '../slots';
import { ArchieMark } from './ArchieMark';
import { selectDestination } from './destinations';
import { setShell, useShell } from './shellState';
import styles from './shell.module.css';

export function connectionText(backend: 'unknown' | 'online' | 'offline', lastError: string | null): string {
  if (backend === 'online') return `Connected to ${typeof location !== 'undefined' && location.hostname ? location.hostname : 'server'}`;
  if (backend === 'offline') return lastError ? `Offline · ${lastError}` : 'Offline';
  return 'Connecting…';
}

export function ConnectionLine() {
  const backend = useConnection((s) => s.backend);
  const lastError = useConnection((s) => s.lastError);
  const text = connectionText(backend, lastError);
  return (
    <span className={styles.connLine}>
      <StatusDot status={backend === 'online' ? 'idle' : backend === 'offline' ? 'disconnected' : 'working'} label={text} />
      <span className={styles.connText}>{text}</span>
    </span>
  );
}

export function AppDrawer() {
  const open = useShell((s) => s.drawerOpen);
  // Initial focus on the drawer's heading, not the search field: focusing a text field on a
  // touch device pops the soft keyboard over the list. Search stays one tap (or Ctrl+K) away.
  const headingRef = useRef<HTMLHeadingElement>(null);
  const close = (): void => {
    setShell({ drawerOpen: false });
  };
  return (
    <NavigationDrawer
      open={open}
      onClose={close}
      aria-label="Navigation"
      initialFocus={headingRef}
      header={
        <div className={styles.drHead}>
          <ArchieMark size={40} />
          <span className={styles.drText}>
            <h2 ref={headingRef} tabIndex={-1} className={styles.drTitle}>
              Archie
            </h2>
            <ConnectionLine />
          </span>
        </div>
      }
      footer={
        <>
          <NavigationDrawerItem
            icon="book_2"
            label="Memory"
            onClick={() => {
              selectDestination('memory', 'compact');
            }}
          />
          <NavigationDrawerItem
            icon="bar_chart"
            label="Visuals"
            onClick={() => {
              selectDestination('visuals', 'compact');
            }}
          />
          <NavigationDrawerItem
            icon="settings"
            label="Settings"
            onClick={() => {
              selectDestination('settings', 'compact');
            }}
          />
        </>
      }
    >
      <HistoryPane variant="drawer" />
    </NavigationDrawer>
  );
}
