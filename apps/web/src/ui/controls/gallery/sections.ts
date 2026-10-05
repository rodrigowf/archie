/**
 * W-03 gallery sections (spec 13 §7 W-03): every control in every variant and state, laid out as
 * the mockups' component sheet. Discovered by the W-02 gallery shell through
 * src/dev/gallery/sections/controls.gallery.tsx. Dev-only: the app never imports this.
 */
import type { GallerySection } from '@/dev/gallery';
import { ActionsGallery } from './actions.gallery';
import { DisplayGallery } from './display.gallery';
import { InputsGallery } from './inputs.gallery';

export const sections: GallerySection[] = [
  {
    id: 'actions',
    title: 'Actions',
    description:
      'Button, IconButton and Fab. Rows 1–3 of each board reproduce the mockups’ component sheet; the state boards force hover, focus and pressed with data-state.',
    order: 60,
    Component: ActionsGallery,
  },
  {
    id: 'inputs',
    title: 'Inputs and selection',
    description:
      'TextField, SearchField, Select (menu on pointer devices, native on touch and compat), Switch, Slider (commits on release), SegmentedButton, Checkbox, Radio. Text is always 16 px.',
    order: 70,
    Component: InputsGallery,
  },
  {
    id: 'display',
    title: 'Display',
    description: 'Chip and Tag, StatusDot, Badge, progress, Card, Divider, List, Disclosure and EmptyState.',
    order: 80,
    Component: DisplayGallery,
  },
];
