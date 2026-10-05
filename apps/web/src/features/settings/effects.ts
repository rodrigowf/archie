/**
 * Device appearance prefs applied to the document (Settings → Appearance). The theme is applied
 * by the app root (W-07 `applyTheme`); reduce motion by the prefs store (`setLowEndForced`).
 * Text size: the type scale is in rem, so the root font size scales all text.
 */
import { useEffect } from 'react';
import { usePrefs, type TextSizePref } from '@/stores';

export const TEXT_SCALE: Record<TextSizePref, string> = {
  small: '',
  default: '',
  large: '112.5%',
  xlarge: '125%',
};

export function applyTextSize(size: TextSizePref, doc: Document = document): void {
  const root = doc.documentElement;
  root.style.fontSize = TEXT_SCALE[size] ?? '';
  root.setAttribute('data-text-size', size);
}

/** Mount once at the app root. */
export function AppearanceEffects(): null {
  const size = usePrefs((p) => p.textSize);
  useEffect(() => {
    applyTextSize(size);
  }, [size]);
  return null;
}
