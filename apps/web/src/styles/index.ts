/**
 * Entry point of `@/styles` (W-02). Importing it once at the root (src/main.tsx) loads every
 * global stylesheet in cascade order and starts the viewport-height sync:
 *   tokens → fonts → reset → base → typography → primitives → motion
 * Size classes (`@media (--compact)` etc.) come from media.css, which vite.shared.ts prepends to
 * every stylesheet; it needs no import.
 */
import '@tokens/tokens.css';
import './fonts.css';
import './reset.css';
import './base.css';
import './typography.css';
import './primitives.css';
import './motion.css';
import { installViewportHeight } from './viewport';

if (typeof window !== 'undefined') installViewportHeight();

export * from './theme';
export * from './viewport';
