/** Status before content (PLAN §3): spinner · check · error · stop, each with distinct colour and shape. */
import type { SubagentStatus } from '@/protocol';
import { Icon, Spinner, cx } from '@/ui/primitives';
import styles from './Subagents.module.css';

const ICON = { done: 'check_circle', failed: 'error', stopped: 'stop_circle' } as const;
const TONE = { done: styles.ok, failed: styles.err, stopped: styles.neutral } as const;

export function SubagentStatusGlyph({ status, size = 18, className }: { status: SubagentStatus; size?: number; className?: string }) {
  if (status === 'running') return <Spinner size={size <= 16 ? 12 : 14} className={cx(styles.glyph, className)} />;
  return <Icon name={ICON[status]} size={size} className={cx(styles.glyph, TONE[status], className)} />;
}
