/**
 * Copy-on-write working copy of a `Conversation` for one reducer step.
 *
 * The spec 12 §4.3 pseudo-code mutates objects in place. Here every entry or block is cloned the
 * first time a step writes to it (then marked "fresh" and written freely for the rest of the
 * step); untouched entries and blocks keep their identity, so memoized rendering only redraws
 * what changed (structural sharing, spec 13 §3.2). Nothing reachable from the input state is
 * ever written.
 *
 * Two lists exist while a history page is converted (§5.1): list 0 is the active list (the page
 * being built, a scratch list), list 1 the entries kept aside. Lookups by id search both.
 */
import type { AssistantEntry, Block, Conversation, Entry, UserEntry } from '../types';

export type Mutable<T> = { -readonly [K in keyof T]: T[K] };

export type MutableAssistant = { id: string; kind: 'assistant'; blocks: Block[] };

/** Location of a block: list (0 active, 1 kept), entry index, block index. */
export interface Loc {
  readonly w: 0 | 1;
  readonly i: number;
  readonly j: number;
}

export class Draft {
  protected readonly s: Mutable<Conversation>;
  private readonly fresh = new Set<object>();
  private ownsEntries = false;
  private keptList: Entry[] | null = null;
  private ownsKept = false;

  constructor(protected readonly prev: Conversation) {
    this.s = { ...prev };
  }

  // ───────────── lists ─────────────

  protected list(w: 0 | 1): readonly Entry[] {
    return w === 0 ? this.s.entries : (this.keptList ?? []);
  }

  private own(w: 0 | 1): Entry[] {
    if (w === 0) {
      if (!this.ownsEntries) {
        this.s.entries = this.s.entries.slice();
        this.ownsEntries = true;
      }
      return this.s.entries as Entry[];
    }
    if (!this.keptList) throw new Error('no kept list');
    if (!this.ownsKept) {
      this.keptList = this.keptList.slice();
      this.ownsKept = true;
    }
    return this.keptList;
  }

  /** Start converting into a scratch list; the current entries are kept aside (list 1). */
  protected beginScratch(): void {
    this.keptList = this.s.entries as Entry[];
    this.ownsKept = this.ownsEntries;
    this.s.entries = [];
    this.ownsEntries = true;
  }

  /** End the scratch conversion: returns [page, kept] and leaves list 0 = kept. */
  protected endScratch(): [Entry[], Entry[]] {
    const page = this.s.entries as Entry[];
    const kept = this.keptList ?? [];
    this.s.entries = kept;
    this.ownsEntries = this.ownsKept;
    this.keptList = null;
    this.ownsKept = false;
    return [page, kept];
  }

  /** Replace list 0 with an array built by this step (all of its new members must be fresh). */
  protected setEntries(entries: Entry[]): void {
    this.s.entries = entries;
    this.ownsEntries = true;
  }

  // ───────────── identity ─────────────

  protected newId(): string {
    const n = this.s.nextId;
    this.s.nextId = n + 1;
    return `k${n}`;
  }

  protected markFresh<T extends object>(o: T): T {
    this.fresh.add(o);
    return o;
  }

  // ───────────── entries ─────────────

  protected pushEntry(e: Entry): void {
    this.own(0).push(this.markFresh(e));
  }

  protected insertEntry(pos: number, e: Entry): void {
    this.own(0).splice(pos, 0, this.markFresh(e));
  }

  protected popEntry(): void {
    this.own(0).pop();
  }

  protected assistantMut(w: 0 | 1, i: number): MutableAssistant {
    const arr = this.own(w);
    const e = arr[i];
    if (!e || e.kind !== 'assistant') throw new Error(`entry ${i} is not an assistant run`);
    if (this.fresh.has(e)) return e as unknown as MutableAssistant;
    const c: MutableAssistant = { ...(e as AssistantEntry), blocks: e.blocks.slice() };
    arr[i] = c as AssistantEntry;
    this.fresh.add(c);
    return c;
  }

  protected userMut(w: 0 | 1, i: number): Mutable<UserEntry> {
    const arr = this.own(w);
    const e = arr[i];
    if (!e || e.kind !== 'user') throw new Error(`entry ${i} is not a user entry`);
    if (this.fresh.has(e)) return e as Mutable<UserEntry>;
    const c: Mutable<UserEntry> = { ...e };
    arr[i] = c;
    this.fresh.add(c);
    return c;
  }

  // ───────────── blocks ─────────────

  protected blockAt(loc: Loc): Block {
    const e = this.list(loc.w)[loc.i] as AssistantEntry;
    return e.blocks[loc.j] as Block;
  }

  protected blockMut(loc: Loc): Mutable<Block> {
    const e = this.assistantMut(loc.w, loc.i);
    const b = e.blocks[loc.j] as Block;
    if (this.fresh.has(b)) return b as Mutable<Block>;
    const c = { ...b } as Mutable<Block>;
    e.blocks[loc.j] = c as Block;
    this.fresh.add(c);
    return c;
  }

  // ───────────── result ─────────────

  /** The next state, or the previous object itself when no field changed. */
  protected finishState(): Conversation {
    const next = this.s as Conversation;
    const keys = Object.keys(next) as (keyof Conversation)[];
    for (const k of keys) if (next[k] !== this.prev[k]) return next;
    return this.prev;
  }
}
