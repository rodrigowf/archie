/**
 * W-14 gallery section (Chats history, Memory tree, Visuals list, inline visual card). The board
 * lives with the feature in src/features/visuals/; this file only lets the shell's glob find it.
 */
import { LibraryGallery } from '../../../features/visuals/Library.gallery';
import type { GallerySection } from '../types';

export const sections: GallerySection[] = [
  {
    id: 'library',
    title: 'History, Memory, Visuals',
    description: 'List-pane destinations on synthetic data: history search + date groups + Open now, memory tree with counts and search, visuals list, inline visual card.',
    order: 90,
    Component: LibraryGallery,
  },
];
