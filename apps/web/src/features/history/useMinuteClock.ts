import { useEffect, useState } from 'react';

/** `Date.now()`, refreshed every minute (date groups, stamps, ages); `fixed` pins it (tests, gallery). */
export function useMinuteClock(fixed?: number): number {
  const [tick, setTick] = useState(() => Date.now());
  useEffect(() => {
    if (fixed !== undefined) return undefined;
    const t = setInterval(() => {
      setTick(Date.now());
    }, 60_000);
    return () => {
      clearInterval(t);
    };
  }, [fixed]);
  return fixed ?? tick;
}
