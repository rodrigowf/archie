/**
 * Deferred highlighting for compat and low-end devices (spec 13 §2.4, §2.7): highlight jobs run
 * in `setTimeout` slices of at most ~8 ms, so a reply with many code blocks never blocks the
 * A7's main thread for long. (`requestIdleCallback` does not exist on Safari.)
 */
const SLICE_MS = 8;

type Job = { run: () => void; cancelled: boolean };

const queue: Job[] = [];
let timer: ReturnType<typeof setTimeout> | null = null;

function now(): number {
  return typeof performance !== 'undefined' ? performance.now() : Date.now();
}

function drain(): void {
  timer = null;
  const started = now();
  while (queue.length > 0) {
    const job = queue.shift() as Job;
    if (!job.cancelled) job.run();
    if (now() - started >= SLICE_MS) break;
  }
  if (queue.length > 0) timer = setTimeout(drain, 0);
}

/** Queues `run` for a later slice. Returns a cancel function. */
export function scheduleHighlight(run: () => void): () => void {
  const job: Job = { run, cancelled: false };
  queue.push(job);
  if (timer === null) timer = setTimeout(drain, 0);
  return () => {
    job.cancelled = true;
  };
}

/** Pending (not cancelled) jobs, for tests. */
export function pendingHighlightJobs(): number {
  return queue.filter((j) => !j.cancelled).length;
}
