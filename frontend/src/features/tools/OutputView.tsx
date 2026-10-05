/**
 * The output region every tool card has (spec 12 §4.5 TC-1/TC-2/TC-3, Rodrigo's R7). It is bound
 * to the `ToolBlock` itself, so it updates in place when the result arrives, also while the card
 * is already expanded:
 *
 *   running    spinner + "Running…" (or the tool's waiting text) + elapsed / progress message
 *   done       the output (ANSI stripped, first 200 lines / 20 KB, then "Show all")
 *   error      the output in error colour
 *   no_result  "No output received" (live) or "No output recorded" (history)
 *
 * An `inferred` result shows a subtle "matched by position" hint (R-4).
 */
import { memo, useMemo, useState, type ReactNode } from 'react';
import type { ToolBlock } from '@/protocol';
import { Spinner, cx } from '@/ui/primitives';
import { countLines, formatClock, plural, stripAnsi, truncateOutput } from './format';
import styles from './ToolCard.module.css';

export interface OutputViewProps {
  readonly block: ToolBlock;
  /** Text while running without output (default "Running…"). */
  readonly runningText?: string;
  /** Seconds since the card started (from useToolTiming), shown while running. */
  readonly runningSeconds?: number | null;
  /** Live lines shown while running (e.g. a delegated agent's activity). */
  readonly feed?: readonly string[];
  /** Feed header, e.g. "Live · session 1a2b3c4d". */
  readonly feedTitle?: string;
  /** Custom rendering of a non-empty output (Markdown for Task, parsed JSON for run_script). */
  readonly renderOutput?: (text: string, isError: boolean) => ReactNode;
  /** A line under the output (Bash: "exit 0"). */
  readonly footer?: ReactNode;
  /** Label of the region header (default "Output"). */
  readonly label?: string;
}

function OutputViewImpl({ block, runningText = 'Running…', runningSeconds, feed, feedTitle, renderOutput, footer, label = 'Output' }: OutputViewProps) {
  const { status, output } = block;
  const isError = status === 'error';
  const hasOutput = output !== null && output !== '';

  if (!hasOutput) {
    if (status === 'running') {
      const clockText = runningSeconds !== null && runningSeconds !== undefined ? formatClock(runningSeconds) : null;
      if (feed && feed.length > 0) {
        return (
          <div className={styles.output} data-kind="feed">
            <div className={styles.outHead}>
              <span>{feedTitle ?? 'Live'}</span>
              {clockText ? <span className={styles.tabular}>{clockText}</span> : null}
            </div>
            {feed.map((line, i) => (
              <span key={i} className={cx(styles.line, i === feed.length - 1 && styles.hi)}>
                {line}
                {i === feed.length - 1 ? <span className={styles.caret} aria-hidden="true" /> : null}
              </span>
            ))}
          </div>
        );
      }
      return (
        <div className={cx(styles.output, styles.waiting)} data-kind="running">
          <Spinner size={12} className={styles.waitSpin} />
          <span>{runningText}</span>
          {clockText ? <span className={cx(styles.tabular, styles.waitClock)}>{`· ${clockText}`}</span> : null}
        </div>
      );
    }
    if (status === 'no_result') {
      return (
        <div className={cx(styles.output, styles.waiting)} data-kind="no-result">
          {block.origin === 'history' ? 'No output recorded' : 'No output received'}
        </div>
      );
    }
    return (
      <div className={cx(styles.output, styles.waiting, isError && styles.errText)} data-kind="empty">
        {isError ? 'Failed with no output' : 'No output'}
        {footer ? <div className={styles.footer}>{footer}</div> : null}
      </div>
    );
  }

  return (
    <TextOutput
      text={output}
      isError={isError}
      inferred={block.inferred === true}
      label={label}
      footer={footer}
      renderOutput={renderOutput}
    />
  );
}

export const OutputView = memo(OutputViewImpl);

interface TextOutputProps {
  readonly text: string;
  readonly isError: boolean;
  readonly inferred: boolean;
  readonly label: string;
  readonly footer?: ReactNode;
  readonly renderOutput?: (text: string, isError: boolean) => ReactNode;
}

function TextOutput({ text, isError, inferred, label, footer, renderOutput }: TextOutputProps) {
  const [showAll, setShowAll] = useState(false);
  const clean = useMemo(() => stripAnsi(text), [text]);
  const cut = useMemo(() => truncateOutput(clean), [clean]);
  const shown = showAll ? clean : cut.text;
  const lines = cut.totalLines;
  const meta = clean.length > 500 ? `${plural(lines, 'line')} · ${clean.length.toLocaleString('en-US')} chars` : null;

  return (
    <div className={cx(styles.output, isError && styles.isError)} data-kind="output">
      {meta || inferred ? (
        <div className={styles.outHead}>
          <span>{label}</span>
          <span>
            {inferred ? <span className={styles.hint}>matched by position</span> : null}
            {inferred && meta ? ' · ' : null}
            {meta}
          </span>
        </div>
      ) : null}
      {renderOutput ? (
        <div className={styles.rich}>{renderOutput(shown, isError)}</div>
      ) : (
        <pre className={cx(styles.pre, isError && styles.errText)}>{shown}</pre>
      )}
      {cut.truncated ? (
        <button type="button" className={cx(styles.showAll, 'has-state-layer')} aria-expanded={showAll} onClick={() => setShowAll(!showAll)}>
          {showAll ? 'Show less' : `Show all ${plural(lines, 'line')}`}
        </button>
      ) : null}
      {footer ? <div className={styles.footer}>{footer}</div> : null}
    </div>
  );
}

/** Number of lines in an output, for header metas. */
export function outputLines(block: ToolBlock): number | null {
  return block.output ? countLines(stripAnsi(block.output)) : null;
}
