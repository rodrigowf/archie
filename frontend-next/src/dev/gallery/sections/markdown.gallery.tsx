/**
 * W-08 gallery section (markdown and code rendering). The board lives with the feature in
 * src/features/markdown/; this file only lets the shell's glob find it.
 */
import { MarkdownGallery } from '../../../features/markdown/Markdown.gallery';
import type { GallerySection } from '../types';

export const sections: GallerySection[] = [
  {
    id: 'markdown',
    title: 'Markdown and code',
    description: 'GFM, tables with inline formatting, code blocks with copy, frontmatter.',
    order: 60,
    Component: MarkdownGallery,
  },
];
