/**
 * Standalone dev entry for the gallery (dev server only: /src/dev/gallery/standalone.html), so the
 * gallery is reachable before the app shell (W-07) routes `#/dev/gallery`. Same boot order as
 * src/main.tsx: polyfills → styles → platform → render.
 */
import '@/platform/polyfills';
import '@/styles';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { initPlatform } from '@/platform';
import { Gallery } from './Gallery';
import { GALLERY_ROUTE, isGalleryRoute } from './route';

initPlatform();
if (!isGalleryRoute()) window.location.hash = GALLERY_ROUTE;

const container = document.getElementById('root');
if (!container) throw new Error('#root element missing');
createRoot(container).render(
  <StrictMode>
    <Gallery />
  </StrictMode>,
);
