/**
 * Entry point of `@/ui/icons` (W-02, spec 13 §3.8). Path data generated from icons.manifest.json
 * by `npm run icons`. Render icons with `<Icon>` from `@/ui/primitives`.
 */
export { ICON_VIEWBOX, filledIconPaths, iconPaths } from './generated';
export type { FilledIconName, IconName } from './generated';

import { filledIconPaths, iconPaths } from './generated';
import type { FilledIconName, IconName } from './generated';

export function isIconName(name: string): name is IconName {
  return Object.prototype.hasOwnProperty.call(iconPaths, name);
}

export function hasFilledVariant(name: string): name is FilledIconName {
  return Object.prototype.hasOwnProperty.call(filledIconPaths, name);
}

/** All bundled icon names, sorted (for the gallery). */
export const iconNames = Object.keys(iconPaths) as IconName[];
