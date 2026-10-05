/**
 * Entry point of `@/dev/gallery` (W-02). The app shell (W-07) or main.tsx mounts <Gallery/> when
 * `isGalleryRoute()` is true; until then, the dev server serves it standalone at
 * /src/dev/gallery/standalone.html.
 */
export { Gallery, collectSections } from './Gallery';
export { GALLERY_ROUTE, galleryHref, isGalleryRoute, parseGalleryRoute } from './route';
export type { GalleryRoute } from './route';
export type { GalleryModule, GallerySection } from './types';
