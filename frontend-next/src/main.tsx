/**
 * Entry (spec 13 §3.1): compat polyfills → platform init → createRoot(<App/>).
 * `@/platform/polyfills` must stay the first import (ES modules evaluate in import order).
 */
import '@/platform/polyfills';
import '@/styles';
import { StrictMode, Suspense, lazy } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from '@/app/App';
import { initPlatform } from '@/platform';

initPlatform();

// Dev gallery of UI primitives and components (W-02), loaded lazily on #/dev/gallery.
const Gallery = lazy(() => import('@/dev/gallery').then((m) => ({ default: m.Gallery })));
const isGallery = window.location.hash.indexOf('#/dev/gallery') === 0;

const container = document.getElementById('root');
if (!container) throw new Error('#root element missing from index.html');

createRoot(container).render(
  <StrictMode>
    {isGallery ? (
      <Suspense fallback={null}>
        <Gallery />
      </Suspense>
    ) : (
      <App />
    )}
  </StrictMode>,
);
