/**
 * PLACEHOLDER hello page (W-01). Handed to W-07 at its start, which replaces this file.
 * It exercises what the W-01 iPad spike needs to prove: a React 18 render, a regex, and a
 * ResizeObserver (polyfilled on compat), plus the remote-console beacon.
 */
import { useEffect, useRef, useState, version as reactVersion, type RefObject } from 'react';
import { generateUUID, getCapabilities, isLowEnd, remoteLog, remoteLogStats } from '@/platform';
import styles from './App.module.css';

const VERSION_PATTERN = /^(\d+)\.(\d+)\.(\d+)(?:\+([0-9a-z]+))?$/;

function windowClass(width: number): 'compact' | 'medium' | 'expanded' {
  if (width < 600) return 'compact';
  if (width < 840) return 'medium';
  return 'expanded';
}

function useElementWidth<T extends HTMLElement>(): [RefObject<T>, number] {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    const ro = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (entry) setWidth(Math.round(entry.contentRect.width));
    });
    ro.observe(el);
    return () => {
      ro.disconnect();
    };
  }, []);
  return [ref, width];
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className={styles.row}>
      <dt className={styles.label}>{label}</dt>
      <dd className={styles.value}>{value}</dd>
    </div>
  );
}

export function App() {
  const [ref, width] = useElementWidth<HTMLElement>();
  const [sample] = useState(() => generateUUID());
  const caps = getCapabilities();
  const versionMatch = VERSION_PATTERN.exec(__APP_VERSION__);

  useEffect(() => {
    const ms = Math.round(performance.now());
    console.info(`[hello] rendered target=${__TARGET__} version=${__APP_VERSION__} in ${ms}ms`);
    remoteLog('perf', `${__TARGET__ === 'compat' ? '[compat] ' : ''}hello first render ${ms}ms`);
  }, []);

  const capRows = (Object.keys(caps) as (keyof typeof caps)[]).map((key) => (
    <Row key={key} label={key} value={caps[key] ? 'yes' : 'no'} />
  ));

  return (
    <main className={styles.page} ref={ref}>
      <header className={styles.header}>
        <span className={styles.mark} aria-hidden="true" />
        <div>
          <h1 className={styles.title}>Archie</h1>
          <p className={styles.subtitle}>frontend-next scaffold · W-01 hello page</p>
        </div>
      </header>

      <section className={styles.card} aria-labelledby="build-h">
        <h2 id="build-h" className={styles.cardTitle}>Build</h2>
        <dl className={styles.list}>
          <Row label="target" value={__TARGET__} />
          <Row label="version" value={__APP_VERSION__} />
          <Row label="built" value={__BUILD_TIME__} />
          <Row label="react" value={reactVersion} />
          <Row
            label="regex check"
            value={versionMatch ? `ok (major ${versionMatch[1] ?? '?'}, sha ${versionMatch[4] ?? 'none'})` : 'FAILED'}
          />
        </dl>
      </section>

      <section className={styles.card} aria-labelledby="device-h">
        <h2 id="device-h" className={styles.cardTitle}>Device</h2>
        <dl className={styles.list}>
          <Row label="ResizeObserver width" value={width > 0 ? `${width}px (${windowClass(width)})` : 'measuring…'} />
          <Row label="low-end mode" value={isLowEnd() ? 'on' : 'off'} />
          <Row label="uuid" value={sample} />
          <Row label="remote console" value={`${window.__archieRemoteConsole?.isEnabled() ? 'on' : 'off'} (sent ${remoteLogStats().sent})`} />
        </dl>
      </section>

      <section className={styles.card} aria-labelledby="caps-h">
        <h2 id="caps-h" className={styles.cardTitle}>Capabilities</h2>
        <dl className={styles.list}>{capRows}</dl>
      </section>
    </main>
  );
}
