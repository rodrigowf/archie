/**
 * A fenced code block: header (language label + Copy) and a horizontally scrollable body
 * (spec 13 §2.4; inv02 F-03).
 *
 * - Every fence renders here, with or without a language (fixes inv02 §6.2: fences without a
 *   language used to render as inline code with no copy button).
 * - Copy goes through `copyText` (@/platform): the Clipboard API when available, otherwise a
 *   hidden textarea + execCommand('copy') (Safari 12, plain-HTTP origins). The label reads
 *   "Copied" for 2 s (LOAD-BEARING inv02 F-03, frontend/src/components/Markdown.tsx:53-58).
 * - Highlighting needs `highlight` (false while streaming), the "Syntax highlighting" pref, and
 *   a known language. The highlighter chunk loads lazily. On compat / low-end devices it runs in
 *   deferred `setTimeout` slices and skips blocks over 8 KB (spec 13 §2.7).
 */
import { memo, useEffect, useRef, useState, type ReactNode } from 'react';
import { copyText, isLowEnd } from '@/platform';
import styles from './CodeBlock.module.css';
import { getLoadedHighlighter, loadHighlighter } from './highlight/loader';
import { scheduleHighlight } from './highlight/scheduler';
import { useMarkdownPrefs } from './prefs';

export interface CodeBlockProps {
  code: string;
  /** Fence language (info string's first word); null/undefined for a plain fence. */
  lang?: string | null;
  /** Allow highlighting (StreamingMarkdown passes false until the reply completes). */
  highlight?: boolean;
}

/** Largest block highlighted on compat / low-end devices (spec 13 §2.4). */
export const LOW_END_HIGHLIGHT_LIMIT = 8 * 1024;
/** Largest block highlighted on main: a safety cap against multi-hundred-KB pastes. */
export const MAIN_HIGHLIGHT_LIMIT = 128 * 1024;

const COPY_RESET_MS = 2000;

type CopyState = 'idle' | 'copied' | 'failed';

function CodeBlockImpl({ code, lang, highlight = true }: CodeBlockProps) {
  const prefs = useMarkdownPrefs();
  const language = lang ? lang.trim() : '';
  const deferred = isLowEnd();
  const limit = deferred ? LOW_END_HIGHLIGHT_LIMIT : MAIN_HIGHLIGHT_LIMIT;
  const wanted = highlight && prefs.syntaxHighlight && language !== '' && code.length <= limit;
  const jobKey = wanted ? `${language}\u0000${code}` : null;

  const [asyncResult, setAsyncResult] = useState<{ key: string; node: ReactNode } | null>(null);

  // Synchronous path: chunk already loaded, not deferred, language registered (cached result).
  let highlighted: ReactNode | null = null;
  if (jobKey !== null) {
    if (asyncResult?.key === jobKey) highlighted = asyncResult.node;
    else if (!deferred) highlighted = getLoadedHighlighter()?.highlightToReact(code, language) ?? null;
  }
  const needsAsync = jobKey !== null && highlighted === null;

  useEffect(() => {
    if (!needsAsync || jobKey === null) return undefined;
    let alive = true;
    let cancel = (): void => undefined;
    loadHighlighter()
      .then((hl) => hl.ensureLanguage(language).then((ok) => (ok ? hl : null)))
      .then((hl) => {
        if (!alive || !hl) return;
        const run = (): void => {
          if (!alive) return;
          const node = hl.highlightToReact(code, language);
          if (node !== null) setAsyncResult({ key: jobKey, node });
        };
        if (deferred) cancel = scheduleHighlight(run);
        else run();
      })
      .catch(() => undefined); // chunk failed to load (offline): stay plain
    return () => {
      alive = false;
      cancel();
    };
  }, [needsAsync, jobKey, code, language, deferred]);

  const [copy, setCopy] = useState<CopyState>('idle');
  const resetTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (resetTimer.current !== null) clearTimeout(resetTimer.current);
    },
    [],
  );

  const onCopy = (): void => {
    void copyText(code).then((ok) => {
      setCopy(ok ? 'copied' : 'failed');
      if (resetTimer.current !== null) clearTimeout(resetTimer.current);
      resetTimer.current = setTimeout(() => setCopy('idle'), COPY_RESET_MS);
    });
  };

  const label = language || 'text';
  const codeClass = highlighted !== null ? `hljs ${styles.code}` : styles.code;

  return (
    <div className={styles.block} data-language={label}>
      <div className={styles.header}>
        <span className={styles.lang}>{label}</span>
        <button
          type="button"
          className={styles.copy}
          onClick={onCopy}
          aria-label={copy === 'idle' ? `Copy ${label} code` : undefined}
        >
          {copy === 'copied' ? 'Copied' : copy === 'failed' ? 'Copy failed' : 'Copy'}
        </button>
      </div>
      {/* Focusable so keyboard users can scroll long lines (axe: scrollable-region-focusable). */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
      <pre className={styles.pre} tabIndex={0}>
        <code className={codeClass} data-highlighted={highlighted !== null ? 'true' : undefined}>
          {highlighted ?? code}
        </code>
      </pre>
    </div>
  );
}

export const CodeBlock = memo(CodeBlockImpl);
