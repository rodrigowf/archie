/**
 * Unified line diff of an Edit (inv02 F-06): `+`/`−` markers, add/remove tints, long unchanged
 * runs folded. The differ loads lazily (diff.ts); until then a plain before/after is shown.
 */
import { memo } from 'react';
import { cx, visuallyHiddenClass } from '@/ui/primitives';
import { useDiff, type DiffLine } from '../diff';
import styles from '../ToolCard.module.css';

const MARK: Record<DiffLine['kind'], string> = { add: '+', del: '−', ctx: ' ', fold: '⋯' };
const LINE_CLASS: Record<DiffLine['kind'], string | undefined> = { add: styles.dAdd, del: styles.dDel, ctx: undefined, fold: styles.dFold };

function Lines({ lines }: { lines: readonly DiffLine[] }) {
  return (
    <>
      {lines.map((l, i) => (
        <span key={i} className={cx(styles.line, LINE_CLASS[l.kind])}>
          <span className={styles.mark} aria-hidden="true">
            {MARK[l.kind]}
          </span>
          {l.kind === 'add' || l.kind === 'del' ? <span className={visuallyHiddenClass}>{l.kind === 'add' ? 'added: ' : 'removed: '}</span> : null}
          {l.text === '' ? ' ' : l.text}
        </span>
      ))}
    </>
  );
}

function splitPlain(text: string, kind: 'add' | 'del'): DiffLine[] {
  const lines = text.split('\n');
  if (lines[lines.length - 1] === '') lines.pop();
  return lines.map((t) => ({ kind, text: t }));
}

function DiffViewImpl({ oldText, newText }: { oldText: string; newText: string }) {
  const diff = useDiff(oldText, newText);
  const lines = diff ? diff.lines : [...splitPlain(oldText, 'del'), ...splitPlain(newText, 'add')];
  return (
    <div className={cx(styles.output, styles.diff)} data-kind="diff" data-ready={diff ? 'true' : 'false'} role="group" aria-label="Diff">
      <Lines lines={lines} />
    </div>
  );
}

export const DiffView = memo(DiffViewImpl);

/** "+4 −1" for the card header; nothing until the differ has loaded. */
export function DiffStat({ oldText, newText }: { oldText: string; newText: string }) {
  const diff = useDiff(oldText, newText);
  if (!diff) return null;
  return (
    <span className={styles.diffStat}>
      <span className={styles.statAdd}>{`+${diff.added}`}</span> <span className={styles.statDel}>{`−${diff.removed}`}</span>
    </span>
  );
}

/** Registry `Meta` of Edit: the diff stat from the tool input. */
export function EditMeta({ input }: { readonly input: Readonly<Record<string, unknown>> }) {
  const o = input.old_string;
  const n = input.new_string;
  return typeof o === 'string' && typeof n === 'string' ? <DiffStat oldText={o} newText={n} /> : null;
}
