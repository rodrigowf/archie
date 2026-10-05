/**
 * W-09 gallery section (conversation view, inline cards, empty states, live mock replay). The
 * board lives with the feature in src/features/conversation/; this file only lets the shell's
 * glob find it.
 */
import { ConversationGallery } from '../../../features/conversation/Conversation.gallery';
import type { GallerySection } from '../types';

export const sections: GallerySection[] = [
  {
    id: 'conversation',
    title: 'Conversation',
    description: 'Message column, inline cards, dividers, voice transcripts, empty states; ?screen=<scene> for full-viewport screenshots.',
    order: 80,
    Component: ConversationGallery,
  },
];
