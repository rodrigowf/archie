import type { ComponentType } from 'react';

/**
 * One gallery section. Each UI package adds `src/dev/gallery/sections/<package>.gallery.tsx`
 * exporting `sections: GallerySection[]`; the shell discovers them with import.meta.glob.
 */
export interface GallerySection {
  /** URL id: `#/dev/gallery/<id>`. */
  id: string;
  title: string;
  description?: string;
  /** Sort key across packages (lower first; default 100). */
  order?: number;
  Component: ComponentType;
}

export interface GalleryModule {
  sections: GallerySection[];
}
