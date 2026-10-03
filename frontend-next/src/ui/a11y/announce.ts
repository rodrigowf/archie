/**
 * Shared `aria-live` region (spec 13 §3.5 `announce()`). One polite and one assertive region,
 * created on first use at the end of <body>, visually hidden and kept exposed while modals are open
 * (`data-a11y-keep`, see hideOthers.ts). Repeating the same message re-announces it: the region is
 * cleared first and the text set on the next tick.
 */
export type Politeness = 'polite' | 'assertive';

const regions: Partial<Record<Politeness, HTMLElement>> = {};
const timers: Partial<Record<Politeness, ReturnType<typeof setTimeout>>> = {};

function region(politeness: Politeness): HTMLElement {
  const existing = regions[politeness];
  if (existing && existing.isConnected) return existing;
  const el = document.createElement('div');
  el.className = 'visually-hidden';
  el.setAttribute('aria-live', politeness);
  el.setAttribute('aria-atomic', 'true');
  el.setAttribute('role', politeness === 'assertive' ? 'alert' : 'status');
  el.setAttribute('data-a11y-keep', '');
  el.setAttribute('data-announcer', politeness);
  document.body.appendChild(el);
  regions[politeness] = el;
  return el;
}

/** Speak `message` through the shared live region. */
export function announce(message: string, politeness: Politeness = 'polite'): void {
  if (typeof document === 'undefined') return;
  const el = region(politeness);
  const pending = timers[politeness];
  if (pending !== undefined) clearTimeout(pending);
  el.textContent = '';
  timers[politeness] = setTimeout(() => {
    el.textContent = message;
    timers[politeness] = undefined;
  }, 50);
}

/** Test seam. */
export function resetAnnouncerForTests(): void {
  for (const p of ['polite', 'assertive'] as const) {
    const t = timers[p];
    if (t !== undefined) clearTimeout(t);
    timers[p] = undefined;
    regions[p]?.remove();
    regions[p] = undefined;
  }
}
