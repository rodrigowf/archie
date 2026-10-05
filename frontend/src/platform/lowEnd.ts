/**
 * Low-end mode (spec 13 §2.7). Sets `html.low-end`, which disables CSS motion and blur and
 * tightens render budgets.
 *
 * LOAD-BEARING inv02 §1.2 (frontend/src/main.tsx:6-11): hardwareConcurrency <= 2 or
 * deviceMemory <= 1 (the iPad mini 2 reports 2 cores; the A300M class too).
 * New: on by default on the compat build; the "Reduce motion" device pref can force it anywhere.
 */
export const LOW_END_CLASS = 'low-end';

export interface LowEndNavigator {
  hardwareConcurrency?: number;
  deviceMemory?: number;
}

export function detectLowEndDevice(nav: LowEndNavigator): boolean {
  const cores = nav.hardwareConcurrency;
  const memory = nav.deviceMemory;
  return (typeof cores === 'number' && cores > 0 && cores <= 2) || (typeof memory === 'number' && memory <= 1);
}

let detected = false;
let forced = false;
let rootEl: HTMLElement | null = null;

function apply(): void {
  if (!rootEl) return;
  if (detected || forced) rootEl.classList.add(LOW_END_CLASS);
  else rootEl.classList.remove(LOW_END_CLASS);
}

export interface InitLowEndOptions {
  target?: 'main' | 'compat';
  nav?: LowEndNavigator;
  root?: HTMLElement;
  forced?: boolean;
}

/** Called once at startup by initPlatform(). Returns the resulting state. */
export function initLowEnd(opts: InitLowEndOptions = {}): boolean {
  const target = opts.target ?? __TARGET__;
  detected = target === 'compat' || detectLowEndDevice(opts.nav ?? (navigator as LowEndNavigator));
  forced = opts.forced ?? forced;
  rootEl = opts.root ?? document.documentElement;
  apply();
  return isLowEnd();
}

/** The "Reduce motion" device pref (Appearance). */
export function setLowEndForced(on: boolean): void {
  forced = on;
  apply();
}

export function isLowEnd(): boolean {
  return detected || forced;
}
