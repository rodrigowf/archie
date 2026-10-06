/**
 * Client-side tool timing for the card's status area (mockups: "0:12" while running, "3.8s" once
 * done). The protocol carries no timestamps, so a live card is timed from the moment this client
 * first saw it running; `tool_progress.elapsed_seconds` (orchestrator) wins when it is larger.
 * History cards and cards first seen finished show no duration (never fabricated).
 *
 * Keyed by the block's stable client id in a bounded module map, so remounts (tab switches,
 * group collapse) keep the clock.
 */
import { useEffect, useState } from 'react';
import type { ToolBlock } from '@/protocol';

interface Span {
  start: number;
  end: number | null;
}

const MAX_SPANS = 1000;
const spans = new Map<string, Span>();

let clock: () => number = () => Date.now();

/** Test hook: replace the clock (returns a restore function). */
export function setTimingClock(now: () => number): () => void {
  const prev = clock;
  clock = now;
  return () => {
    clock = prev;
  };
}

export function resetTiming(): void {
  spans.clear();
}

function track(block: ToolBlock): Span | null {
  let span = spans.get(block.id);
  if (!span) {
    if (block.status !== 'running' || block.origin !== 'live') return null;
    span = { start: clock(), end: null };
    spans.set(block.id, span);
    if (spans.size > MAX_SPANS) {
      const oldest = spans.keys().next().value;
      if (oldest !== undefined) spans.delete(oldest);
    }
  }
  if (block.status !== 'running' && span.end === null) span.end = clock();
  return span;
}

export interface ToolTiming {
  /** Seconds since the card started, while running. */
  readonly runningSeconds: number | null;
  /** Total duration in ms, once finished. */
  readonly durationMs: number | null;
}

/** Elapsed / duration for a card; re-renders once a second while the tool runs. */
export function useToolTiming(block: ToolBlock): ToolTiming {
  const running = block.status === 'running';
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!running) return undefined;
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [running]);

  const span = track(block);
  const progress = block.progress?.elapsed_seconds;
  if (running) {
    const local = span ? (clock() - span.start) / 1000 : null;
    const best = progress !== undefined && (local === null || progress > local) ? progress : local;
    return { runningSeconds: best, durationMs: null };
  }
  return { runningSeconds: null, durationMs: span && span.end !== null ? span.end - span.start : null };
}
