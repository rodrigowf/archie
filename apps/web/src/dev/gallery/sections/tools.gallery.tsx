/**
 * W-10 gallery section (tool cards and "N steps" groups). The board lives with the feature in
 * src/features/tools/; this file only lets the shell's glob find it.
 */
import { ToolsGallery } from '../../../features/tools/Tools.gallery';
import type { GallerySection } from '../types';

export const sections: GallerySection[] = [
  {
    id: 'tools',
    title: 'Tool cards',
    description: 'Every tool in running, done and error; N-steps groups; live replay; R7 output states.',
    order: 70,
    Component: ToolsGallery,
  },
];
