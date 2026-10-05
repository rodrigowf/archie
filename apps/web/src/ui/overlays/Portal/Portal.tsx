/**
 * Portal into `#overlay-root` (spec 13 §3.5, §4.2). The app shell (W-07) renders
 * `<div id="overlay-root"/>`; before it exists (galleries, tests) the root is created at the end of
 * <body>.
 */
import { useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

export const OVERLAY_ROOT_ID = 'overlay-root';

export function getOverlayRoot(): HTMLElement {
  let el = document.getElementById(OVERLAY_ROOT_ID);
  if (!el) {
    el = document.createElement('div');
    el.id = OVERLAY_ROOT_ID;
    document.body.appendChild(el);
  }
  return el;
}

export interface PortalProps {
  children: ReactNode;
  /** Render into this element instead of `#overlay-root`. */
  container?: HTMLElement | null;
}

export function Portal({ children, container }: PortalProps) {
  const [root] = useState<HTMLElement>(() => container ?? getOverlayRoot());
  return createPortal(children, container ?? root);
}
