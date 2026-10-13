/**
 * A shared one-second clock for live elapsed times: it ticks only while some running agent shows
 * one (no timer otherwise). `useNow(false)` never re-renders on its own.
 */
import { useSyncExternalStore } from 'react';

let now = Date.now();
const subscribers = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | null = null;

function subscribe(cb: () => void): () => void {
  subscribers.add(cb);
  if (!timer) {
    now = Date.now();
    timer = setInterval(() => {
      now = Date.now();
      subscribers.forEach((f) => f());
    }, 1000);
  }
  return () => {
    subscribers.delete(cb);
    if (subscribers.size === 0 && timer) {
      clearInterval(timer);
      timer = null;
    }
  };
}

const idle = (): (() => void) => () => undefined;
const read = (): number => now;

export function useNow(active: boolean): number {
  return useSyncExternalStore(active ? subscribe : idle, read, read);
}
