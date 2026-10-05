/**
 * Inline visual card (mockups tablet, "Visuals inline"): a visual Archie made shows as a card with
 * Open and Show on TV — the same action as the viewer's top bar. Exported for the conversation
 * (W-09) to place under a turn that produced a visualization; Show on TV is hidden unless the
 * BX-2 probe says available (spec 13 §3.7).
 */
import { useState } from 'react';
import { parseServerTime } from '@/features/history';
import { formatRelativeTime } from '@/platform';
import { useCapabilities } from '@/stores';
import { Button } from '@/ui/controls';
import { showOnTv } from './viz';
import styles from './visuals.module.css';

export interface VisualCardProps {
  readonly path: string;
  readonly title: string;
  /** ISO `modified` from the list (offset-aware). */
  readonly modified?: string | null;
  /** Overrides the second line (e.g. "showing on Living-room TV"). */
  readonly status?: string | null;
  readonly onOpen: () => void;
}

/** The small bar-chart glyph of the mockup (decorative). */
function ChartGlyph() {
  return (
    <svg className={styles.cardGlyph} viewBox="0 0 72 56" width="72" height="56" aria-hidden="true" focusable="false">
      <g className={styles.glyphBars}>
        <rect x="9" y="25" width="6" height="22" rx="2" />
        <rect x="18" y="28" width="6" height="19" rx="2" />
        <rect x="27" y="22" width="6" height="25" rx="2" />
        <rect x="36" y="26" width="6" height="21" rx="2" />
        <rect x="45" y="19" width="6" height="28" rx="2" />
        <rect x="54" y="14" width="6" height="33" rx="2" className={styles.glyphPeak} />
      </g>
    </svg>
  );
}

export function VisualCard({ path, title, modified, status, onOpen }: VisualCardProps) {
  const castAvailable = useCapabilities((s) => s.castAvailable);
  const [casting, setCasting] = useState(false);
  const t = parseServerTime(modified ?? null);
  const line = status ?? (isFinite(t) ? `Visual · updated ${formatRelativeTime(t)}` : 'Visual');
  return (
    <div className={styles.card} data-visual={path}>
      <ChartGlyph />
      <span className={styles.cardText}>
        <span className={styles.cardTitle}>{title}</span>
        <span className={styles.cardLine}>{line}</span>
      </span>
      <span className={styles.cardActions}>
        <Button variant="outlined" size="small" onClick={onOpen}>
          Open
        </Button>
        {castAvailable ? (
          <Button
            variant="tonal"
            size="small"
            icon="cast"
            loading={casting}
            onClick={() => {
              setCasting(true);
              void showOnTv(path, title).finally(() => {
                setCasting(false);
              });
            }}
          >
            Show on TV
          </Button>
        ) : null}
      </span>
    </div>
  );
}
