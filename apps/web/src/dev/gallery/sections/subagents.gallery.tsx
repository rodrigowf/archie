/**
 * Subagent gallery section (spec 12 §4.8). The board lives with the feature in
 * src/features/subagents/; this file only lets the shell's glob find it.
 */
import { SubagentsGallery } from '../../../features/subagents/Subagents.gallery';
import type { GallerySection } from '../types';

export const sections: GallerySection[] = [
  {
    id: 'subagents',
    title: 'Subagents',
    description: 'Inline agent cards, the auto-created agent tabs, an agent tab (breadcrumb, read-only bar) and the agents sheet.',
    order: 75,
    Component: SubagentsGallery,
  },
];
