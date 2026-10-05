/**
 * Lazy wrappers: the settings pages and the session sheet are their own chunk, fetched the first
 * time Settings or a session sheet opens (spec 13 §5.4).
 */
import { lazy, Suspense } from 'react';
import type { SettingsPageId } from './pages';
import type { SessionSettingsSheetProps } from './session/SessionSettingsSheet';
import { closeSessionSettings, useSessionSettingsTarget } from './session/sessionSettingsStore';

const loadView = () => import('./SettingsView');
const loadSheet = () => import('./session/SessionSettingsSheet');
const LazyView = lazy(loadView);
const LazySheet = lazy(loadSheet);

/** Start fetching the settings chunk (e.g. on hover of the Settings destination). */
export function preloadSettings(): void {
  void loadView();
  void loadSheet();
}

export interface SettingsScreenProps {
  page?: string | null;
  /** Route to a page (null = the list). Without it, rows do nothing (gallery). */
  onNavigate?: (page: SettingsPageId | null) => void;
  load?: boolean;
  layout?: 'two-pane' | 'pushed';
}

const noop = (): void => undefined;

/** Kept here (not in parts.tsx) so the settings CSS stays in the lazy chunk. */
function Loading({ label }: { label: string }) {
  return (
    <p role="status" style={{ margin: 16, opacity: 0.7 }}>
      {label}
    </p>
  );
}

export function SettingsScreen({ page = null, onNavigate = noop, load, layout }: SettingsScreenProps) {
  return (
    <Suspense fallback={<Loading label="Loading settings…" />}>
      <LazyView page={page} onNavigate={onNavigate} {...(load === undefined ? {} : { load })} {...(layout ? { layout } : {})} />
    </Suspense>
  );
}

export function SessionSettingsSheet(props: SessionSettingsSheetProps) {
  if (!props.open) return null;
  return (
    <Suspense fallback={null}>
      <LazySheet {...props} />
    </Suspense>
  );
}

/** Renders the sheet for the session chosen with `openSessionSettings(localId)`. Mount once. */
export function SessionSettingsHost({ onOpenSettings }: { onOpenSettings?: SessionSettingsSheetProps['onOpenSettings'] }) {
  const localId = useSessionSettingsTarget();
  if (!localId) return null;
  return (
    <SessionSettingsSheet
      key={localId}
      localId={localId}
      open
      onClose={closeSessionSettings}
      {...(onOpenSettings
        ? {
            onOpenSettings: (p: 'working-directories' | 'mcp-servers') => {
              closeSessionSettings();
              onOpenSettings(p);
            },
          }
        : {})}
    />
  );
}
