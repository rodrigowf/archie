/**
 * W-13 gallery section (Settings home and pages, pushed page, sign-in screen). The board lives
 * with the feature in src/features/settings/; this file only lets the shell's glob find it.
 */
import { SettingsGallery } from '../../../features/settings/Settings.gallery';
import type { GallerySection } from '../types';

export const sections: GallerySection[] = [
  {
    id: 'settings',
    title: 'Settings',
    description: 'IA §7 settings: home with current values, two-pane and pushed detail pages, the sign-in screen (R5).',
    order: 90,
    Component: SettingsGallery,
  },
];
