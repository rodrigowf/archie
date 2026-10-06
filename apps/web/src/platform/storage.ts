/**
 * Safe wrappers around localStorage / sessionStorage. Every access is try/catch'd: storage can
 * be missing, blocked, or throw on quota (Safari private mode). On failure the wrapper keeps the
 * value in memory for the page's lifetime, so callers never see an exception.
 */
export interface SafeStorage {
  get(key: string): string | null;
  set(key: string, value: string): boolean;
  remove(key: string): void;
  getJSON<T>(key: string, fallback: T): T;
  setJSON(key: string, value: unknown): boolean;
  /** true when the real storage is being used (not the in-memory fallback). */
  readonly persistent: boolean;
}

export function createSafeStorage(getStorage: () => Storage | null | undefined): SafeStorage {
  const memory = new Map<string, string>();
  let persistent = true;

  function backend(): Storage | null {
    if (!persistent) return null;
    try {
      const s = getStorage();
      if (!s) {
        persistent = false;
        return null;
      }
      return s;
    } catch {
      persistent = false;
      return null;
    }
  }

  const api: SafeStorage = {
    get(key) {
      const s = backend();
      if (s) {
        try {
          return s.getItem(key);
        } catch {
          // fall back to memory
        }
      }
      return memory.has(key) ? (memory.get(key) ?? null) : null;
    },
    set(key, value) {
      memory.set(key, value);
      const s = backend();
      if (!s) return false;
      try {
        s.setItem(key, value);
        return true;
      } catch {
        return false;
      }
    },
    remove(key) {
      memory.delete(key);
      const s = backend();
      if (!s) return;
      try {
        s.removeItem(key);
      } catch {
        // ignore
      }
    },
    getJSON<T>(key: string, fallback: T): T {
      const raw = api.get(key);
      if (raw === null) return fallback;
      try {
        return JSON.parse(raw) as T;
      } catch {
        return fallback;
      }
    },
    setJSON(key, value) {
      let raw: string;
      try {
        raw = JSON.stringify(value);
      } catch {
        return false;
      }
      return api.set(key, raw);
    },
    get persistent() {
      return backend() !== null;
    },
  };
  return api;
}

export const localStore: SafeStorage = createSafeStorage(() =>
  typeof window !== 'undefined' ? window.localStorage : null,
);
export const sessionStore: SafeStorage = createSafeStorage(() =>
  typeof window !== 'undefined' ? window.sessionStorage : null,
);
