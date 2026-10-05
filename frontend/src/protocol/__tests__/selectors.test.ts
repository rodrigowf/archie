/** Selectors and checkpoint helpers. */
import { describe, expect, it } from 'vitest';
import type { Block, ToolBlock } from '../index';
import {
  advanceCheckpoint,
  canResume,
  checkpointStorageKey,
  contextUsage,
  createMemoryCheckpointStore,
  deriveTitle,
  groupToolSteps,
  hasSdkId,
  isBusy,
  isDuplicateSeq,
  isRunLive,
  mergeResumeState,
  pendingPermission,
  unmatchedResults,
} from '../index';
import { agent, feed, orch } from './util';

const tool = (id: string): ToolBlock => ({
  id,
  type: 'tool',
  tool_use_id: id,
  tool_name: 'Bash',
  tool_input: {},
  status: 'done',
  output: '',
  scope: 'turn',
  origin: 'live',
});
const text = (id: string): Block => ({ id, type: 'text', text: id, streaming: false, scope: 'turn', origin: 'live' });

describe('groupToolSteps', () => {
  it('groups ≥ 2 consecutive tools, keeps order, live only while nothing follows', () => {
    const blocks = [text('a'), tool('t1'), tool('t2'), text('b'), tool('t3'), tool('t4'), tool('t5')];
    const items = groupToolSteps(blocks, { live: true });
    expect(items.map((i) => (i.kind === 'steps' ? `steps:${i.blocks.length}:${i.live}:${i.key}` : i.block.id))).toEqual([
      'a',
      'steps:2:false:t1',
      'b',
      'steps:3:true:t3',
    ]);
    expect(groupToolSteps([tool('x'), text('y')]).map((i) => i.kind)).toEqual(['block', 'block']);
    expect(groupToolSteps(blocks, { enabled: false })).toHaveLength(7);
    expect(groupToolSteps([tool('p'), tool('q')], { minSize: 3 }).map((i) => i.kind)).toEqual(['block', 'block']);
  });
});

describe('conversation selectors', () => {
  it('isBusy, isRunLive, hasSdkId', () => {
    const c = feed(agent(), { type: 'status', status: 'processing' }, { type: 'text_delta', text: 'x' }).conv;
    expect(isBusy(c)).toBe(true);
    expect(isRunLive(c, 0)).toBe(true);
    expect(isRunLive(c, 1)).toBe(false);
    expect(isBusy(agent())).toBe(false);
    expect(hasSdkId(agent({ sdkId: null }))).toBe(false);
  });

  it('pendingPermission returns the newest pending block (PM-3)', () => {
    const req = (rid: string) => ({ type: 'permission_request', request_id: rid, tool_name: 'ExitPlanMode', tool_input: {} });
    let c = feed(agent(), { type: 'status', status: 'processing' }, req('r1'), req('r2')).conv;
    expect(pendingPermission(c)?.request_id).toBe('r2');
    c = feed(c, { type: 'permission_resolved', request_id: 'r2', decision: 'deny', responder: 'orchestrator' }).conv;
    expect(pendingPermission(c)?.request_id).toBe('r1');
    expect(pendingPermission(feed(c, { type: 'user_message', text: 'x' }).conv)).toBeNull();
  });

  it('unmatchedResults hides history orphans while older pages exist (R-9)', () => {
    let c = feed(orch(), { type: 'tool_result', tool_use_id: 'live', output: 'a' }).conv;
    c = { ...c, orphanResults: c.orphanResults.concat([{ tool_use_id: 'h', output: 'b', is_error: false, origin: 'history' }]), history: { ...c.history, hasMore: true } };
    expect(unmatchedResults(c).count).toBe(1);
    expect(unmatchedResults({ ...c, history: { ...c.history, hasMore: false } }).count).toBe(2);
  });

  it('contextUsage thresholds (§6.4)', () => {
    const at = (t: number | null, w: number | null) => contextUsage({ ...agent(), counters: { cost: 0, turns: 0, contextTokens: t, contextWindow: w } });
    expect(at(null, 1000)).toEqual({ percent: null, level: 'normal' });
    expect(at(100, null)).toEqual({ percent: null, level: 'normal' });
    expect(at(400, 1000)).toEqual({ percent: 40, level: 'normal' });
    expect(at(500, 1000)).toEqual({ percent: 50, level: 'caution' });
    expect(at(800, 1000)).toEqual({ percent: 80, level: 'warning' });
  });

  it('deriveTitle (MC-2)', () => {
    const list = [
      { session_id: 'sdk-1', title: 'By sdk' },
      { session_id: 'L2', local_id: 'L2', title: 'By local' },
    ];
    expect(deriveTitle(list, { sdkId: 'sdk-1', localId: 'x' }, 'New')).toBe('By sdk');
    expect(deriveTitle(list, { sdkId: null, localId: 'L2' }, 'New')).toBe('By local');
    expect(deriveTitle(list, { sdkId: 'zz', localId: 'zz' }, 'New')).toBe('New');
    // A live session with no history file yet is listed as "(active session)": not a title.
    const live = [{ session_id: 'L3', local_id: 'L3', title: '(active session)' }];
    expect(deriveTitle(live, { sdkId: 'L3', localId: 'L3' }, 'New agent session')).toBe('New agent session');
    expect(deriveTitle(live, { sdkId: null, localId: 'L3' }, 'New agent session')).toBe('New agent session');
  });
});

describe('checkpoint helpers (§3.6, T-10)', () => {
  it('dedupe, advance, resume_state seeding', () => {
    expect(isDuplicateSeq(null, 's', 1)).toBe(false);
    expect(isDuplicateSeq({ stream_id: 's', seq: 3 }, 's', 3)).toBe(true);
    expect(isDuplicateSeq({ stream_id: 's', seq: 3 }, 't', 1)).toBe(false);
    const cp = { stream_id: 's', seq: 3 };
    expect(advanceCheckpoint(cp, 's', 2)).toBe(cp);
    expect(advanceCheckpoint(cp, 's', 4)).toEqual({ stream_id: 's', seq: 4 });
    expect(advanceCheckpoint(cp, 't', 1)).toEqual({ stream_id: 't', seq: 1 });
    expect(mergeResumeState(cp, { stream_id: 's', next_seq: 2 })).toEqual(cp);
    expect(mergeResumeState(cp, { stream_id: 's', next_seq: 9 })).toEqual({ stream_id: 's', seq: 8 });
    expect(mergeResumeState(null, { stream_id: 'n', next_seq: 1 })).toEqual({ stream_id: 'n', seq: 0 });
  });

  it('canResume needs a Claude agent, a checkpoint and in-process history', () => {
    const a = { ...agent(), checkpoint: { stream_id: 's', seq: 1 } };
    expect(canResume(a)).toBe(false);
    expect(canResume({ ...a, history: { ...a.history, loaded: true } })).toBe(true);
    expect(canResume({ ...orch(), checkpoint: a.checkpoint, history: { ...a.history, loaded: true } })).toBe(false);
  });

  it('memory store and storage key', () => {
    const s = createMemoryCheckpointStore();
    expect(s.load('L')).toBeNull();
    s.save('L', { stream_id: 's', seq: 2 });
    expect(s.load('L')).toEqual({ stream_id: 's', seq: 2 });
    s.save('L', null);
    expect(s.load('L')).toBeNull();
    expect(checkpointStorageKey('L')).toBe('ws-resume-checkpoint:L');
  });
});
