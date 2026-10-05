/** Shared building blocks of the per-tool bodies: a field list and a bounded code view. */
import type { ReactNode } from 'react';
import { CodeBlock } from '@/features/markdown';
import { cx } from '@/ui/primitives';
import styles from '../ToolCard.module.css';

export type FieldRow = readonly [label: string, value: ReactNode, mono?: boolean];

/** Label/value rows as a description list (`dl` with `dt`/`dd`); empty values are skipped. */
export function Fields({ rows }: { rows: ReadonlyArray<FieldRow | null | false | undefined> }) {
  const visible = rows.filter((r): r is FieldRow => !!r && r[1] !== undefined && r[1] !== null && r[1] !== '');
  if (visible.length === 0) return null;
  return (
    <dl className={styles.fields}>
      {visible.map(([label, value, mono]) => (
        <div key={label} className={styles.fieldRow}>
          <dt className={styles.fieldLabel}>{label}</dt>
          <dd className={cx(styles.fieldValue, mono && styles.mono)}>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** A code block (header, Copy, lazy highlight) capped in height; scrolls inside. */
export function CodeView({ code, lang, label }: { code: string; lang: string | null; label?: string }) {
  return (
    <div className={styles.codeView} data-label={label}>
      <CodeBlock code={code} lang={lang} />
    </div>
  );
}

/** Pre-formatted prose input (prompts, messages), capped in height. */
export function PreText({ children }: { children: string }) {
  return <pre className={cx(styles.pre, styles.preInput)}>{children}</pre>;
}

/** The body wrapper: input details above the output region. */
export function Section({ children }: { children: ReactNode }) {
  return <div className={styles.section}>{children}</div>;
}
