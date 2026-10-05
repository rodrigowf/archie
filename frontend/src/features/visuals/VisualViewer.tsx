/**
 * Visual viewer (spec 13 §3.6 `features/visuals`, mockups phone (h); inv02 F-36 VizPanel).
 *
 * - **[LOAD-BEARING]** inv02 F-36 (frontend/src/components/VizPanel.tsx:60): the iframe sandbox
 *   is exactly `allow-scripts allow-same-origin allow-popups allow-forms allow-modals`.
 *   Same-origin lets a visualization fetch the backend and use storage; leaving out
 *   `allow-top-navigation` and `allow-downloads` stops a framed page from navigating the app away
 *   or starting downloads (VZ-3).
 * - **[LOAD-BEARING]** inv02 F-36 (VizPanel.tsx:15-17): Reload bumps the iframe's React `key`,
 *   i.e. remounts it, which reloads across origins and history states (VZ-4).
 * - **[LOAD-BEARING]** inv02 F-36 / spec 13 §4.4: the iframe stays mounted while the panel is
 *   hidden (`hidden` never unmounts it), so interactive visualizations keep their state.
 * - Toolbar (mockup h): "Updated 2 h ago · folder", **Show on TV** (only when the BX-2 probe says
 *   available — hidden, not disabled, spec 13 §3.7), ⋮ Reload / Open in browser / Copy link.
 */
import { useRef, useState } from 'react';
import { parseServerTime } from '@/features/history';
import { copyText, formatRelativeTime } from '@/platform';
import { showSnackbar, useCapabilities, useCatalog } from '@/stores';
import { Button, IconButton } from '@/ui/controls';
import { Menu, MenuItem } from '@/ui/overlays';
import { findVisual, showOnTv, vizFolder, vizHref } from './viz';
import styles from './visuals.module.css';

/** The exact sandbox tokens (F-36, VZ-3). Exported for tests. */
export const VIZ_SANDBOX = 'allow-scripts allow-same-origin allow-popups allow-forms allow-modals';

export interface VisualViewerProps {
  /** Path relative to context/public/ (the identity). */
  readonly path: string;
  /** The list's `url` (unencoded); falls back to the list entry, then `/<path>`. */
  readonly url: string | undefined;
  /** The panel is hidden (a background tab): the iframe stays mounted. */
  readonly hidden: boolean;
}

export function VisualViewer({ path, url, hidden }: VisualViewerProps) {
  const item = useCatalog((s) => findVisual(s.visuals.items, path));
  const castAvailable = useCapabilities((s) => s.castAvailable);
  const [reloadKey, setReloadKey] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);
  const [casting, setCasting] = useState(false);
  const menuRef = useRef<HTMLButtonElement>(null);

  const href = vizHref(path, url ?? item?.url);
  const title = item?.title ?? path;
  const modified = item ? parseServerTime(item.modified) : NaN;
  const meta = [isFinite(modified) ? `Updated ${formatRelativeTime(modified)}` : null, vizFolder(path)].filter(Boolean).join(' · ');

  const reload = (): void => {
    setReloadKey((k) => k + 1);
  };

  return (
    <section className={styles.viewer} aria-label={title} data-hidden={hidden ? '' : undefined}>
      <div className={styles.viewerBar}>
        <span className={styles.viewerMeta} title={href}>
          {meta}
        </span>
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
        <IconButton
          ref={menuRef}
          icon="more_vert"
          size="small"
          aria-label="Visual menu"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          onClick={() => {
            setMenuOpen((o) => !o);
          }}
        />
        <Menu
          open={menuOpen}
          onClose={() => {
            setMenuOpen(false);
          }}
          anchor={menuRef}
          placement="bottom-end"
          minWidth={210}
          aria-label="Visual menu"
        >
          <MenuItem icon="refresh" onSelect={reload}>
            Reload
          </MenuItem>
          <MenuItem
            icon="open_in_new"
            onSelect={() => {
              window.open(href, '_blank', 'noopener,noreferrer');
            }}
          >
            Open in browser
          </MenuItem>
          <MenuItem
            icon="link"
            onSelect={() => {
              void copyText(href).then((ok) => {
                showSnackbar(ok ? 'Link copied' : 'Could not copy the link', ok ? {} : { tone: 'error' });
              });
            }}
          >
            Copy link
          </MenuItem>
        </Menu>
      </div>
      <div className={styles.frameWrap}>
        <iframe key={reloadKey} className={styles.frame} title={title} src={href} sandbox={VIZ_SANDBOX} data-reload={reloadKey} />
      </div>
    </section>
  );
}
