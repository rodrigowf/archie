/**
 * Component gallery shell (spec 13 §3.1, W-02), route `#/dev/gallery`. Sections are discovered from
 * `./sections/*.gallery.tsx` (each UI package adds its own). Looks like the approved mockups'
 * review board: page head with theme switch, TOC chips, numbered sections.
 */
import { useEffect, useState } from 'react';
import { applyTheme, getThemeMode, type ThemeMode } from '@/styles';
import { Center, Cluster, Icon, ScrollArea, Stack } from '@/ui/primitives';
import { ArchieMark } from './ArchieMark';
import styles from './Gallery.module.css';
import { galleryHref, parseGalleryRoute } from './route';
import type { GalleryModule, GallerySection } from './types';

const modules = import.meta.glob<GalleryModule>('./sections/*.gallery.tsx', { eager: true });

export function collectSections(mods: Record<string, GalleryModule> = modules): GallerySection[] {
  return Object.keys(mods)
    .sort()
    .flatMap((k) => mods[k]?.sections ?? [])
    .sort((a, b) => (a.order ?? 100) - (b.order ?? 100));
}

const THEME_BUTTONS: { mode: ThemeMode; label: string; icon: 'brightness_auto' | 'dark_mode' | 'light_mode' }[] = [
  { mode: 'system', label: 'System', icon: 'brightness_auto' },
  { mode: 'dark', label: 'Dark', icon: 'dark_mode' },
  { mode: 'light', label: 'Light', icon: 'light_mode' },
];

function useHash(): string {
  const [hash, setHash] = useState(() => window.location.hash);
  useEffect(() => {
    const on = (): void => {
      setHash(window.location.hash);
    };
    window.addEventListener('hashchange', on);
    return () => {
      window.removeEventListener('hashchange', on);
    };
  }, []);
  return hash;
}

export function Gallery({ sections = collectSections() }: { sections?: GallerySection[] }) {
  const route = parseGalleryRoute(useHash());
  const [theme, setTheme] = useState<ThemeMode>(() => route.theme ?? getThemeMode());

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);
  useEffect(() => {
    const on = (): void => {
      const t = parseGalleryRoute(window.location.hash).theme;
      if (t) setTheme(t);
    };
    window.addEventListener('hashchange', on);
    return () => {
      window.removeEventListener('hashchange', on);
    };
  }, []);
  useEffect(() => {
    document.title = 'Archie · gallery';
    const root = document.documentElement;
    root.classList.toggle('dev-gallery-full', route.full);
    return () => {
      root.classList.remove('dev-gallery-full');
    };
  }, [route.full]);

  const visible = route.section ? sections.filter((s) => s.id === route.section) : sections;
  const numberOf = (s: GallerySection): number => sections.indexOf(s) + 1;

  const page = (
    <Center as="main" max={1292} gutter="4" className={styles.inner}>
      <Stack as="header" space="6" className={styles.head}>
        <div className={styles.brandRow}>
          <div className={styles.brand} style={{ display: 'flex', alignItems: 'center' }}>
            <ArchieMark size={56} className={styles.mark} />
            <div>
              <p className={styles.eyebrow}>frontend · component gallery</p>
              <h1 className={styles.title}>Component gallery</h1>
            </div>
          </div>
          <div className={styles.seg} role="group" aria-label="Theme">
            {THEME_BUTTONS.map((b) => (
              <button
                key={b.mode}
                type="button"
                className={`${styles.segButton} has-state-layer`}
                aria-pressed={theme === b.mode}
                onClick={() => {
                  setTheme(b.mode);
                }}
              >
                <Icon name={b.icon} size={18} />
                {b.label}
              </button>
            ))}
          </div>
        </div>
        <p className={styles.lede}>
          Tokens, type, color roles, icons and layout primitives as they render in this build. Every value comes from{' '}
          <code>design/tokens</code>; no flex gap anywhere, so this page is also a Safari 12 check.
        </p>
        <Cluster as="ul" aria-label="Sections">
          <li>
            <a className={`${styles.toc} has-state-layer`} href={galleryHref(null)} aria-current={route.section ? undefined : 'page'}>
              All
            </a>
          </li>
          {sections.map((s) => (
            <li key={s.id}>
              <a
                className={`${styles.toc} has-state-layer`}
                href={galleryHref(s.id)}
                aria-current={route.section === s.id ? 'page' : undefined}
              >
                <span className={styles.tocNo}>{numberOf(s)}</span>
                {s.title}
              </a>
            </li>
          ))}
        </Cluster>
      </Stack>
      <div className={styles.sections}>
        {visible.map((s) => (
          <section key={s.id} id={`gallery-${s.id}`} className={styles.sec} aria-labelledby={`gallery-${s.id}-h`}>
            <div className={styles.secHead}>
              <span className={styles.secNo}>{numberOf(s)}</span>
              <div>
                <h2 id={`gallery-${s.id}-h`} className={styles.secTitle}>
                  {s.title}
                </h2>
                {s.description ? <p className={styles.secText}>{s.description}</p> : null}
              </div>
            </div>
            <s.Component />
          </section>
        ))}
        {visible.length === 0 ? <p>No section “{route.section}”.</p> : null}
      </div>
    </Center>
  );

  return route.full ? (
    <div className={`${styles.page} ${styles.full}`}>{page}</div>
  ) : (
    <ScrollArea fill className={styles.page}>
      {page}
    </ScrollArea>
  );
}
