/**
 * Viewport height (spec 13 §2.6). Layout never uses vh/dvh: iOS 12's 100vh includes the hidden
 * toolbar. `--app-height` on <html> follows window.innerHeight on resize and orientation change
 * (base.css falls back to 100% before this runs).
 */
export const APP_HEIGHT_VAR = '--app-height';

let installed: (() => void) | null = null;

export function syncViewportHeight(doc: Document = document, win: Window = window): number {
  const h = win.innerHeight;
  if (h > 0) doc.documentElement.style.setProperty(APP_HEIGHT_VAR, `${h}px`);
  return h;
}

/** Idempotent. Returns an uninstall function. */
export function installViewportHeight(doc: Document = document, win: Window = window): () => void {
  if (installed) return installed;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const onResize = (): void => {
    syncViewportHeight(doc, win);
  };
  // iOS reports the final innerHeight only after the rotation animation; sync again shortly after.
  const onOrientation = (): void => {
    syncViewportHeight(doc, win);
    if (timer !== undefined) clearTimeout(timer);
    timer = setTimeout(onResize, 300);
  };
  win.addEventListener('resize', onResize);
  win.addEventListener('orientationchange', onOrientation);
  syncViewportHeight(doc, win);
  installed = () => {
    win.removeEventListener('resize', onResize);
    win.removeEventListener('orientationchange', onOrientation);
    if (timer !== undefined) clearTimeout(timer);
    installed = null;
  };
  return installed;
}
