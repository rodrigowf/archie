/**
 * `(stream_id, seq)` resume cursor (spec 12 §3.6, T-10; inventory 02 F-24).
 *
 * T-10 is normative: `resume_from` may only be sent when the conversation's entries were built in
 * this process from the same stream. The machine keeps the checkpoint in memory (conversation
 * state) and decides that with `canResume`. A page reload rebuilds the conversation from REST, so
 * a checkpoint persisted on its own (the old web app's per-event sessionStorage write, F-24) must
 * NOT be sent afterwards. `CheckpointStore` exists for runtimes that persist a full entries
 * snapshot together with its checkpoint (T-10 "restoring both is equivalent to in memory"); they
 * must write it at most on page hide / app stop, never per event (A-8.14).
 */
import { seqCapable, type Checkpoint, type Conversation } from '../types';
import type { ResumeState } from '../wire/server';

/** SEQ-1: same stream and not newer than the checkpoint. */
export function isDuplicateSeq(cp: Checkpoint | null, streamId: string, seq: number): boolean {
  return cp !== null && cp.stream_id === streamId && seq <= cp.seq;
}

/** The checkpoint after applying a frame stamped `(streamId, seq)` (SEQ-4: a new stream replaces it). */
export function advanceCheckpoint(cp: Checkpoint | null, streamId: string, seq: number): Checkpoint {
  if (cp && cp.stream_id === streamId && cp.seq >= seq) return cp;
  return { stream_id: streamId, seq };
}

/** `session_started.resume_state` → the checkpoint it implies (`next_seq - 1`). */
export function checkpointFromResumeState(rs: ResumeState): Checkpoint {
  return { stream_id: rs.stream_id, seq: rs.next_seq - 1 };
}

/** Seed from `resume_state` without moving backwards on the same stream (§3.6 onSessionStarted). */
export function mergeResumeState(cp: Checkpoint | null, rs: ResumeState): Checkpoint {
  const seeded = checkpointFromResumeState(rs);
  if (cp && cp.stream_id === rs.stream_id) return { stream_id: rs.stream_id, seq: Math.max(cp.seq, seeded.seq) };
  return seeded;
}

/** T-10: may this conversation send `resume_from` on its next `start`? */
export function canResume(conv: Conversation): boolean {
  return seqCapable(conv.ref) && conv.checkpoint !== null && conv.history.loaded;
}

/** Storage for an entries snapshot + its checkpoint, saved together (T-10). Injected by the runtime. */
export interface CheckpointStore {
  load(localId: string): Checkpoint | null;
  save(localId: string, checkpoint: Checkpoint | null): void;
}

/** The key the old web app used (F-24), kept so a migration can clear stale values. */
export function checkpointStorageKey(localId: string): string {
  return `ws-resume-checkpoint:${localId}`;
}

/** An in-memory `CheckpointStore` (tests, and runtimes that keep no snapshot). */
export function createMemoryCheckpointStore(): CheckpointStore {
  const map = new Map<string, Checkpoint>();
  return {
    load: (id) => map.get(id) ?? null,
    save: (id, cp) => {
      if (cp) map.set(id, cp);
      else map.delete(id);
    },
  };
}
