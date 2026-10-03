/**
 * Entry (spec 13 §3.1): compat polyfills → platform init → createRoot(<App/>).
 * `@/platform/polyfills` must stay the first import (ES modules evaluate in import order).
 */
import '@/platform/polyfills';
import '@tokens/tokens.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from '@/app/App';
import { initPlatform } from '@/platform';

initPlatform();

const container = document.getElementById('root');
if (!container) throw new Error('#root element missing from index.html');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
