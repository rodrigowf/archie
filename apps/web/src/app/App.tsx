/**
 * App root (W-07, spec 13 §4.2): AuthGate → AppShell. Starts the services once (tabs hydrate,
 * watcher socket, pool sync, probes), applies the theme pref, and mirrors navigation to the hash.
 *
 * P-1: nothing here ever closes a session. There is no unload / pagehide handler and unmounting
 * the app does not stop the services; sessions keep running in the backend with zero frontends.
 */
import { useEffect } from 'react';
import { startServices } from '@/services';
import { usePrefs } from '@/stores';
import { applyTheme } from '@/styles';
import { AppShell } from './AppShell';
import { installHashSync } from './navigation/route';
import { installTurnNotifications } from './notifications/turnNotifier';
import { AuthGate } from './slots';

export interface AppProps {
  /** Start the services on mount (default true; component tests drive the stores directly). */
  services?: boolean;
}

export function App({ services = true }: AppProps) {
  const theme = usePrefs((p) => p.theme);
  useEffect(() => {
    applyTheme(theme);
  }, [theme]);
  useEffect(() => installHashSync(), []);
  useEffect(() => {
    if (services) startServices(); // also loads the session and visuals lists (titles)
  }, [services]);
  // "Agent session finished" notices (Settings → Notifications) and their clicks
  useEffect(() => (services ? installTurnNotifications() : undefined), [services]);
  return (
    <AuthGate>
      <AppShell />
    </AuthGate>
  );
}
