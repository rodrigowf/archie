/**
 * Markdown for a reply that is still streaming (spec 13 §5.1 items 3–4).
 *
 * While `streaming`, the source is split by `splitBlocks()`: every closed chunk is a memoized
 * <MarkdownBody> keyed by `index:hash(source)`, so a delta re-parses only the unfinished tail.
 * No highlighting while streaming (open fences render as plain monospace). When the block
 * completes (`text_complete` is authoritative), one full parse replaces the chunks, so the final
 * output is exact CommonMark (e.g. reference-style links that the split view cannot resolve),
 * with highlighting.
 *
 * All chunks render inside one root element with no wrappers, so the DOM has the same shape as
 * the full parse and the prose CSS (block spacing) applies unchanged.
 */
import { memo, useMemo } from 'react';
import type { LinkResolver } from './links';
import { Markdown, MarkdownBody, markdownRootClass } from './Markdown';
import { hashString, splitBlocks } from './splitBlocks';

export interface StreamingMarkdownProps {
  source: string;
  streaming: boolean;
  linkResolver?: LinkResolver;
  className?: string;
}

function StreamingMarkdownImpl({ source, streaming, linkResolver, className }: StreamingMarkdownProps) {
  const split = useMemo(() => (streaming ? splitBlocks(source) : null), [source, streaming]);

  if (!split) return <Markdown source={source} linkResolver={linkResolver} className={className} />;

  const rootClass = className ? `${markdownRootClass} ${className}` : markdownRootClass;
  return (
    <div className={rootClass} data-streaming="true">
      {split.stable.map((chunk, i) => (
        <MarkdownBody
          key={`${i}:${hashString(chunk.source)}`}
          source={chunk.source}
          linkResolver={linkResolver}
          highlight={false}
        />
      ))}
      {split.tail.source.trim() !== '' && (
        <MarkdownBody key="tail" source={split.tail.source} linkResolver={linkResolver} highlight={false} />
      )}
    </div>
  );
}

export const StreamingMarkdown = memo(StreamingMarkdownImpl);
