/** Helpers shared by the server pages: the config-or-loading gate and the "saving" state. */
import type { ReactNode } from 'react';
import type { ServerConfig } from '@/services';
import { setPref, showSnackbar, useConnection, useServerConfig, type Prefs } from '@/stores';
import { refreshSettings } from '../controller';
import { LoadError, Loading, Notice } from '../parts';

/** Renders `children(config)` once the server config is loaded; otherwise loading / error states. */
export function WithConfig({ children }: { children: (config: ServerConfig) => ReactNode }) {
  const config = useServerConfig((s) => s.config);
  const loading = useServerConfig((s) => s.loading);
  const error = useServerConfig((s) => s.error);
  const offline = useConnection((s) => s.backend === 'offline');
  if (config)
    return (
      <>
        {offline ? (
          <Notice tone="warning" title="Offline">
            These are the last values read from the server. Saving needs the connection.
          </Notice>
        ) : null}
        {children(config)}
      </>
    );
  if (error)
    return (
      <LoadError
        message={error}
        onRetry={() => {
          void refreshSettings();
        }}
      />
    );
  return <Loading label={loading ? 'Loading server settings…' : 'Loading…'} />;
}

/** True while any server save is in flight: the page's controls are disabled (CFG-1). */
export function useSaving(): boolean {
  return useServerConfig((s) => s.saving !== null);
}

/** Device prefs save at once; confirm like every other save (IA §7). */
export function setDevicePref<K extends keyof Prefs>(key: K, value: Prefs[K]): void {
  setPref(key, value);
  showSnackbar('Saved', { durationMs: 2000 });
}
