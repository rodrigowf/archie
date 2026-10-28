/**
 * The ChatGPT mark (OpenAI blossom), for labelling conversations imported from a ChatGPT data
 * export (`harness: "chatgpt"`, read-only). A standalone brand asset, deliberately NOT part of the
 * generated Material Symbols pipeline (`src/ui/icons/`, `npm run icons`), which only carries
 * Material glyphs.
 *
 * The glyph itself lives in `chatgpt-mark.svg` (shipped in `public-main/` and `public-compat/`) and
 * is applied by the global `.chatgpt-mark` class as a CSS mask — see `src/styles/primitives.css`.
 * Inlining its single ~1.6 kB path here instead would blow the initial-JS budget (spec 13 §5.4) for
 * a badge used on two non-critical surfaces. Masking keeps `currentColor`, so one asset serves both
 * colour schemes, and Vite rewrites the mask url() per base (the compat build points at
 * `/compat/chatgpt-mark.svg`).
 *
 * Source: Wikimedia Commons `File:ChatGPT-Logo.svg` — public domain (simple geometry), OpenAI
 * trademark. Used only to identify imported ChatGPT conversations, never to brand Archie itself.
 */
import { cx } from '../layout/types';

export function ChatGptMark({ size = 16, className, title }: { size?: number; className?: string; title?: string }) {
  return (
    <span
      className={cx('chatgpt-mark', className)}
      style={{ fontSize: `${size}px` }}
      role={title ? 'img' : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    />
  );
}
