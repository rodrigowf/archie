/**
 * Markdown gallery section (W-08): the whole corpus, a frozen mid-stream state, a replayable
 * stream and a memory document. Used for QA screenshots (qa/W-08.md) and mountable by the W-02
 * dev gallery shell. Dev-only: nothing in the app imports it.
 */
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { corpusFiles, languagesDoc } from './__fixtures__/corpus';
import { longCode } from './__fixtures__/languageSamples';
import { splitFrontmatter, summarizeFrontmatter } from './frontmatter';
import { createMemoryLinkResolver } from './links';
import { Markdown } from './Markdown';
import styles from './Markdown.gallery.module.css';
import { StreamingMarkdown } from './StreamingMarkdown';

const STREAM_SOURCE = [corpusFiles['inline.md'], corpusFiles['tables.md'], corpusFiles['code.md']].join('\n\n');
// Cut inside code.md's plain fence: the python fence above is closed, this one is still open.
const OPEN_FENCE_CUT = 'plain fence without a language\nsecond';
const MID_STREAM = STREAM_SOURCE.slice(0, STREAM_SOURCE.indexOf(OPEN_FENCE_CUT) + OPEN_FENCE_CUT.length);
const LONG = '```python\n' + longCode(120) + '\n```';
const MEMORY_PATH = 'assistant/architecture/voice_subsystem.md';

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section className={styles.section} id={id} aria-labelledby={`${id}-title`}>
      <h2 className={styles.sectionTitle} id={`${id}-title`}>
        {title}
      </h2>
      <div className={styles.surface}>{children}</div>
    </section>
  );
}

function StreamReplay() {
  const [n, setN] = useState(STREAM_SOURCE.length);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const stop = (): void => {
    if (timer.current !== null) clearInterval(timer.current);
    timer.current = null;
  };
  useEffect(() => stop, []);
  const replay = (): void => {
    stop();
    setN(0);
    // ~30 deltas/s of 12 characters (spec 13 §5.4 streaming scenario).
    timer.current = setInterval(() => {
      setN((prev) => {
        const next = Math.min(prev + 12, STREAM_SOURCE.length);
        if (next >= STREAM_SOURCE.length) stop();
        return next;
      });
    }, 33);
  };
  const streaming = n < STREAM_SOURCE.length;
  return (
    <>
      <button type="button" className={styles.button} onClick={replay}>
        {streaming ? 'Streaming…' : 'Replay stream'}
      </button>
      <StreamingMarkdown source={STREAM_SOURCE.slice(0, n)} streaming={streaming} />
    </>
  );
}

function MemoryDoc() {
  const [opened, setOpened] = useState<string | null>(null);
  const resolver = useMemo(() => createMemoryLinkResolver(MEMORY_PATH, setOpened), []);
  const { frontmatter, body } = splitFrontmatter(corpusFiles['memory-doc.md'] ?? '');
  const summary = summarizeFrontmatter(frontmatter ?? '');
  return (
    <>
      <details className={styles.frontmatter}>
        <summary>
          Frontmatter · {summary.fields.category} · {summary.listLengths.references ?? 0} refs
        </summary>
        <pre>{frontmatter}</pre>
      </details>
      <Markdown source={body} linkResolver={resolver} />
      <p className={styles.note} aria-live="polite">
        {opened ? `Would open memory file: ${opened}` : 'Relative links open in the app.'}
      </p>
    </>
  );
}

export function MarkdownGallery() {
  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Markdown (W-08) · {__TARGET__}</h1>
      <Section id="md-inline" title="Inline formatting, lists, quotes, footnotes">
        <Markdown source={corpusFiles['inline.md'] ?? ''} />
      </Section>
      <Section id="md-tables" title="Tables">
        <Markdown source={corpusFiles['tables.md'] ?? ''} />
      </Section>
      <Section id="md-tasks" title="Task lists and autolinks">
        <Markdown source={(corpusFiles['tasklists.md'] ?? '') + '\n\n' + (corpusFiles['autolinks.md'] ?? '')} />
      </Section>
      <Section id="md-code" title="Code fences">
        <Markdown source={corpusFiles['code.md'] ?? ''} />
      </Section>
      <Section id="md-midstream" title="Mid-stream (open fence, no highlighting)">
        <StreamingMarkdown source={MID_STREAM} streaming />
      </Section>
      <Section id="md-stream" title="Stream replay">
        <StreamReplay />
      </Section>
      <Section id="md-memory" title="Memory document">
        <MemoryDoc />
      </Section>
      <Section id="md-long" title="Long code">
        <Markdown source={LONG} />
      </Section>
      <Section id="md-languages" title="Languages">
        <Markdown source={languagesDoc} />
      </Section>
    </div>
  );
}
