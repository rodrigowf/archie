/**
 * W-12 gallery section (voice dock in every state). The board lives with the feature in
 * src/features/voice/; this file only lets the shell's glob find it.
 */
import { VoiceGallery } from '../../../features/voice/Voice.gallery';
import type { GallerySection } from '../types';

export const sections: GallerySection[] = [
  {
    id: 'voice',
    title: 'Voice',
    description: 'Voice dock: listening, speaking, thinking, tools, reconnecting (P-2), errors, active elsewhere; ?screen=<state> for screenshots.',
    order: 85,
    Component: VoiceGallery,
  },
];
