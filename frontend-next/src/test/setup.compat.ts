/**
 * Setup for the `compat` Vitest project: installs the RegExp guard before any test module
 * (and therefore any grammar registration) runs. See regexpGuard.ts.
 */
import { installRegExpGuard } from './regexpGuard';

installRegExpGuard();
