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
 *
 * Pictures the tool returned (R-10 / TC-5: a Read of an image, a screenshot) show above the text
 * output, fitted to the card; a click opens the full-size image in a new tab. The body mounts only
 * once the card is first expanded, so nothing loads for a folded card.
 */
import { memo, useMemo, useState, type ReactNode } from 'react';
import type { ToolBlock, ToolImage } from '@/protocol';
import { httpUrl } from '@/services';
import { Spinner, cx } from '@/ui/primitives';
import { baseName, countLines, formatClock, plural, str, stripAnsi, truncateOutput } from './format';
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

  if (block.images?.length) {
    const file = str(block.tool_input, 'file_path') ?? str(block.tool_input, 'path');
    return (
      <>
        <ToolImages images={block.images} name={file ? baseName(file) : null} />
        {hasOutput ? (
          <TextOutput text={output} isError={isError} inferred={block.inferred === true} label={label} footer={footer} renderOutput={renderOutput} />
        ) : null}
      </>
    );
  }

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

function ToolImages({ images, name }: { readonly images: readonly ToolImage[]; readonly name: string | null }) {
  return (
    <div className={styles.images} data-kind="images">
      {images.map((image, i) => (
        <ToolImageView
          key={`${i}:${image.url}`}
          image={image}
          alt={(name ?? 'Tool image') + (images.length > 1 ? ` (${i + 1} of ${images.length})` : '')}
        />
      ))}
    </div>
  );
}

function ToolImageView({ image, alt }: { readonly image: ToolImage; readonly alt: string }) {
  const [failed, setFailed] = useState(false);
  const src = httpUrl(image.url);
  if (failed) {
    return (
      <div className={cx(styles.output, styles.waiting)} data-kind="image-error">
        Image unavailable
      </div>
    );
  }
  return (
    <a className={styles.imageLink} href={src} target="_blank" rel="noopener noreferrer" title="Open full size">
      <img className={styles.image} src={src} alt={alt} decoding="async" onError={() => setFailed(true)} />
    </a>
  );
}

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
