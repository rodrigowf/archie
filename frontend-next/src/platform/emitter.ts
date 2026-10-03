/**
 * Minimal typed event emitter. Replaces `new EventTarget()` (not constructible on Safari 12;
 * banned by ESLint). `Events` maps event names to payload types.
 */
export type Listener<T> = (payload: T) => void;

export class Emitter<Events extends Record<string, unknown>> {
  private readonly listeners = new Map<keyof Events, Set<Listener<never>>>();

  on<K extends keyof Events>(type: K, fn: Listener<Events[K]>): () => void {
    let set = this.listeners.get(type);
    if (!set) {
      set = new Set();
      this.listeners.set(type, set);
    }
    set.add(fn as Listener<never>);
    return () => {
      this.off(type, fn);
    };
  }

  once<K extends keyof Events>(type: K, fn: Listener<Events[K]>): () => void {
    const off = this.on(type, (payload) => {
      off();
      fn(payload);
    });
    return off;
  }

  off<K extends keyof Events>(type: K, fn: Listener<Events[K]>): void {
    const set = this.listeners.get(type);
    if (!set) return;
    set.delete(fn as Listener<never>);
    if (set.size === 0) this.listeners.delete(type);
  }

  /** Calls every listener registered at emit time. A throwing listener does not stop the others. */
  emit<K extends keyof Events>(type: K, payload: Events[K]): void {
    const set = this.listeners.get(type);
    if (!set) return;
    for (const fn of Array.from(set)) {
      try {
        (fn as Listener<Events[K]>)(payload);
      } catch (err) {
        console.error('[emitter] listener for', String(type), 'threw', err);
      }
    }
  }

  listenerCount<K extends keyof Events>(type: K): number {
    return this.listeners.get(type)?.size ?? 0;
  }

  clear(): void {
    this.listeners.clear();
  }
}
