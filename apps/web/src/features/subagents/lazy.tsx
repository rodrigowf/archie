/**
 * Lazy wrappers of the agent views (`view.tsx`). The conversation panel mounts them only once its
 * session has a subagent, so a session without agents never downloads the chunk.
 */
import { lazy, Suspense } from 'react';
import type { SessionStore } from '@/stores';

const load = () => import('./view');
const LazyTabs = lazy(() => load().then((m) => ({ default: m.SubagentTabsView })));
const LazyBody = lazy(() => load().then((m) => ({ default: m.SubagentBodyView })));
const LazyBar = lazy(() => load().then((m) => ({ default: m.SubagentBarView })));

/** Start fetching the agent views (the first subagent of a session appeared). */
export function preloadSubagentViews(): void {
  void load();
}

/** Main + one tab per agent, above the message list (plus the state announcements). */
export function SubagentTabs({ localId }: { localId: string }) {
  return (
    <Suspense fallback={null}>
      <LazyTabs localId={localId} />
    </Suspense>
  );
}

/** An agent tab's body: breadcrumb and transcript. */
export function SubagentBody(props: { localId: string; store: SessionStore; toolUseId: string; hidden: boolean }) {
  return (
    <Suspense fallback={null}>
      <LazyBody {...props} />
    </Suspense>
  );
}

/** Replaces the composer while an agent's tab is shown. */
export function SubagentBar(props: { localId: string; toolUseId: string }) {
  return (
    <Suspense fallback={null}>
      <LazyBar {...props} />
    </Suspense>
  );
}
