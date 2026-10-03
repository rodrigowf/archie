/**
 * Icon by name with an optional FILL=1 variant, for props typed as plain `IconName` (the
 * primitive `Icon` only accepts `filled` for names that have a filled path). Falls back to the
 * outline glyph when the manifest has no filled variant.
 */
import { hasFilledVariant } from '@/ui/icons';
import { Icon, type IconName } from '@/ui/primitives';

export interface GlyphProps {
  name: IconName;
  filled?: boolean;
  size?: number;
  className?: string;
}

export function Glyph({ name, filled, size, className }: GlyphProps) {
  return filled && hasFilledVariant(name) ? (
    <Icon name={name} filled size={size} className={className} />
  ) : (
    <Icon name={name} size={size} className={className} />
  );
}
