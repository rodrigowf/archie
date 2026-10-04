/**
 * W-14 gallery board: the three list destinations (Chats history, Memory tree, Visuals list) and
 * the inline visual card, on synthetic data seeded into the catalog store. Dev-only. Documents
 * and the viewer need a backend (mock server) and are checked in the app instead.
 */
import { useState, type ReactNode } from 'react';
import { HistoryPane, type OpenNowItem } from '@/features/history';
import { MemoryPane } from '@/features/memory';
import type { MemoryNode, SessionInfo, VisualizationInfo } from '@/services';
import { patchCapabilities, setCatalogItems } from '@/stores';
import { StatusDot } from '@/ui/controls';
import { Icon } from '@/ui/primitives';
import { VisualCard } from './VisualCard';
import { VisualsPane } from './VisualsPane';
import styles from './Library.gallery.module.css';

const NOW = Date.now();
const iso = (msAgo: number): string => new Date(NOW - msAgo).toISOString();
const H = 3600_000;
const D = 24 * H;

const SESSIONS: SessionInfo[] = [
  { session_id: 'g1', started_at: iso(2 * H), last_activity: iso(2 * H), title: 'Weekly energy report', message_count: 12, is_orchestrator: true, provider: 'claude', local_id: null },
  { session_id: 'g2', started_at: iso(5 * H), last_activity: iso(5 * H), title: 'Fix context-sync delete race', message_count: 30, is_orchestrator: false, provider: 'claude', local_id: null },
  { session_id: 'g3', started_at: iso(D + H), last_activity: iso(D + H), title: 'Morning briefing', message_count: 4, is_orchestrator: true, provider: 'claude', local_id: null },
  { session_id: 'g4', started_at: iso(3 * D), last_activity: iso(3 * D), title: 'Compat build for iPad mini', message_count: 18, is_orchestrator: false, provider: 'qwen', local_id: null },
  { session_id: 'g5', started_at: iso(20 * D), last_activity: iso(20 * D), title: 'Jetson thermal check', message_count: 6, is_orchestrator: false, provider: 'gemini', local_id: null },
];

const f = (path: string): MemoryNode => ({ name: path.split('/').pop() ?? path, path, is_dir: false, children: null });
const MEMORY: MemoryNode[] = [
  {
    name: 'assistant',
    path: 'assistant',
    is_dir: true,
    children: [
      {
        name: 'architecture',
        path: 'assistant/architecture',
        is_dir: true,
        children: [f('assistant/architecture/voice_subsystem.md'), f('assistant/architecture/wakeword_subsystem.md')],
      },
      { name: 'devices', path: 'assistant/devices', is_dir: true, children: [f('assistant/devices/fire_tv.md'), f('assistant/devices/ipad.md')] },
    ],
  },
  { name: 'projects', path: 'projects', is_dir: true, children: [f('projects/garden.md')] },
  f('MEMORY.md'),
];

const VISUALS: VisualizationInfo[] = [
  { path: 'visuals/weekly-energy.html', url: '/visuals/weekly-energy.html', title: 'Weekly energy usage', created: iso(2 * H), modified: iso(2 * H), size: 1 },
  { path: 'solar-system/index.html', url: '/solar-system/index.html', title: 'Solar system', created: iso(4 * D), modified: iso(4 * D), size: 1 },
  { path: 'movie-night.html', url: '/movie-night.html', title: 'Movie night plan', created: iso(9 * D), modified: iso(9 * D), size: 1 },
];

const OPEN: OpenNowItem[] = [
  { id: 'o1', title: 'Refactor voice module', sdkId: null, isArchie: false, providerLabel: 'Claude', statusLabel: 'Ready', leading: <Icon name="terminal" size={20} />, status: <StatusDot status="idle" label="Ready" /> },
  { id: 'o2', title: 'Energy dashboard', sdkId: null, isArchie: false, providerLabel: 'Qwen', statusLabel: 'Using Bash…', leading: <Icon name="terminal" size={20} />, status: <StatusDot status="working" label="Using Bash…" /> },
];

function Board({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={styles.board} aria-label={title}>
      <h3 className={styles.title}>{title}</h3>
      <div className={styles.pane}>{children}</div>
    </section>
  );
}

/** Seed before the panes mount (their own effects would otherwise fetch the real lists first). */
function seed(): true {
  setCatalogItems('sessions', SESSIONS);
  setCatalogItems('memory', MEMORY);
  setCatalogItems('visuals', VISUALS);
  patchCapabilities({ castAvailable: true });
  return true;
}

export function LibraryGallery() {
  useState(seed);
  return (
    <div className={styles.grid}>
      <Board title="Chats (list pane)">
        <HistoryPane variant="pane" openNow={OPEN} activeId="o1" />
      </Board>
      <Board title="Memory">
        <MemoryPane selectedPath="assistant/architecture/voice_subsystem.md" />
      </Board>
      <Board title="Visuals">
        <VisualsPane selectedPath="visuals/weekly-energy.html" />
      </Board>
      <Board title="Inline visual card">
        <VisualCard path="visuals/weekly-energy.html" title="Weekly energy usage" modified={iso(2 * H)} onOpen={() => undefined} />
      </Board>
    </div>
  );
}
