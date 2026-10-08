/** Shared pieces of a tab's presentation: leading icon, provider chip, status indicator. */
import type { Provider } from '@/protocol';
import { useProviderLabel, type Tab } from '@/stores';
import { StatusDot, Tag } from '@/ui/controls';
import { Icon } from '@/ui/primitives';
import { ArchieMark } from '../shell/ArchieMark';
import { kindIcon, type TabSummary } from './tabSummary';
import styles from './workspace.module.css';

/**
 * The tab's kind icon. `unseen` (P-6 background activity) adds a small badge on the icon, so a
 * tab never shows two dots side by side: the status indicator stays the only dot.
 */
export function TabLeading({ tab, size = 20, unseen = false }: { tab: Pick<Tab, 'kind'>; size?: number; unseen?: boolean }) {
  const icon = tab.kind === 'archie' ? <ArchieMark size={size} /> : <Icon name={kindIcon(tab.kind)} size={size} />;
  if (!unseen) return icon;
  return (
    <span className={styles.leadWrap} data-unseen="">
      {icon}
      <span className={styles.unseenBadge} aria-hidden="true" />
    </span>
  );
}

export function ProviderTag({ provider }: { provider: Provider | null | undefined }) {
  const label = useProviderLabel()(provider);
  return label ? <Tag>{label}</Tag> : null;
}

/** Spinner working, dot idle, hand waiting, warning disconnected, outline dot off (never color alone). */
export function StatusGlyph({ summary }: { summary: TabSummary | null }) {
  if (!summary) return null;
  return <StatusDot status={summary.indicator} label={summary.label} />;
}
