/**
 * A visual the assistant made, inline in the conversation (mockups tablet frame: "A visual Archie
 * made shows as a card with Open and Show on TV, the same action as the full-screen top bar").
 *
 * Detection is by the tool call that wrote it: a finished Write / write_file / Edit whose path is
 * an HTML file under the served `public/` folder (`context/public/<path>`, served at `/<path>`,
 * the same `path` / `url` pair as `GET /api/visualizations`). "Show on TV" is hidden, not
 * disabled, when the cast probe says it is unavailable (spec 13 §3.7).
 */
import { memo, useState } from 'react';
import type { ToolBlock } from '@/protocol';
import { castVisualization, errorMessage } from '@/services';
import { openTab, showSnackbar, useCapabilities, useCatalog } from '@/stores';
import { Button } from '@/ui/controls';
import { Icon } from '@/ui/primitives';
import styles from '../Conversation.module.css';

export interface VisualRef {
  /** Path relative to the public root, e.g. `visualizations/energy/index.html`. */
  readonly path: string;
  readonly url: string;
}

const WRITE_TOOLS = ['Write', 'Edit', 'MultiEdit', 'write_file', 'edit_file', 'create_file', 'replace'];
const PUBLIC_HTML = /(?:^|\/)public\/(.+\.html?)$/i;

/** The visual a finished tool call wrote, if any. */
export function visualFromTool(block: ToolBlock): VisualRef | null {
  if (block.status !== 'done' || WRITE_TOOLS.indexOf(block.tool_name) < 0) return null;
  const input = block.tool_input;
  const raw = typeof input.file_path === 'string' ? input.file_path : typeof input.path === 'string' ? input.path : null;
  if (!raw) return null;
  const m = PUBLIC_HTML.exec(raw);
  if (!m || !m[1]) return null;
  return { path: m[1], url: `/${m[1]}` };
}

function fallbackTitle(path: string): string {
  const parts = path.split('/').filter(Boolean);
  let last = parts[parts.length - 1] ?? path;
  if (/^index\.html?$/i.test(last) && parts.length > 1) last = parts[parts.length - 2] as string;
  return last.replace(/\.html?$/i, '').replace(/[-_]+/g, ' ');
}

function VisualCardImpl({ visual }: { visual: VisualRef }) {
  const listed = useCatalog((c) => c.visuals.items.find((v) => v.path === visual.path));
  const castAvailable = useCapabilities((c) => c.castAvailable);
  const [casting, setCasting] = useState(false);
  const title = listed?.title ?? fallbackTitle(visual.path);
  const open = (): void => {
    openTab({ id: `viz:${visual.path}`, kind: 'visual', path: visual.path, url: listed?.url ?? visual.url, titleHint: title }, { focus: true });
  };
  const cast = async (): Promise<void> => {
    setCasting(true);
    try {
      const r = await castVisualization(visual.path);
      showSnackbar(r.ok ? 'Showing on TV' : r.message || "Couldn't show it on the TV");
    } catch (err) {
      showSnackbar(errorMessage(err));
    } finally {
      setCasting(false);
    }
  };
  return (
    <div className={styles.visual} data-block="visual">
      <span className={styles.visualThumb} aria-hidden="true">
        <Icon name="bar_chart" size={28} />
      </span>
      <span className={styles.visualText}>
        <span className={styles.visualTitle}>{title}</span>
        <span className={styles.visualSub}>Visual · {visual.path}</span>
      </span>
      <span className={styles.visualActions}>
        <Button variant="outlined" size="small" onClick={open}>
          Open
        </Button>
        {castAvailable ? (
          <Button variant="tonal" size="small" icon="cast" loading={casting} onClick={() => void cast()}>
            Show on TV
          </Button>
        ) : null}
      </span>
    </div>
  );
}

export const VisualCard = memo(VisualCardImpl);
