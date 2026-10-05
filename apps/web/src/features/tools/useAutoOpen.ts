import { useCallback, useState } from 'react';

/**
 * Open state that follows `auto` until the user toggles it (IA §9.3 rule): a step group or card is
 * open while live and folds by itself once text follows. Whenever `auto` changes, a manual choice
 * is dropped, so a live group the user kept open still collapses when the run moves on, and a
 * folded one opens again if it becomes live.
 */
export function useAutoOpen(auto: boolean): readonly [boolean, (open: boolean) => void] {
  const [manual, setManual] = useState<boolean | null>(null);
  const [prevAuto, setPrevAuto] = useState(auto);
  if (prevAuto !== auto) {
    // Adjusting state while rendering (React's "storing information from previous renders").
    setPrevAuto(auto);
    if (manual !== null) setManual(null);
  }
  const open = prevAuto === auto && manual !== null ? manual : auto;
  const set = useCallback((v: boolean) => setManual(v), []);
  return [open, set] as const;
}
