/** Frames: tolerant decoding (T-1, T-3) and client messages (T-2). */
import { describe, expect, it } from 'vitest';
import { coerceFrame, decodeFrame, encodeClientMessage, utf8Decode } from '../index';
import { binaryFrame } from './harness';

describe('decodeFrame', () => {
  it('decodes binary UTF-8 JSON (ArrayBuffer and views) and text frames', () => {
    const f = { type: 'text_delta', text: 'olá 🌍', seq: 1, stream_id: 'L1:1' };
    expect(decodeFrame(binaryFrame(f))).toEqual({ ok: true, frame: f });
    expect(decodeFrame(new Uint8Array(binaryFrame(f)))).toEqual({ ok: true, frame: f });
    expect(decodeFrame(JSON.stringify(f))).toEqual({ ok: true, frame: f });
  });

  it('drops non-JSON, non-objects and frames without a string type (T-3)', () => {
    expect(decodeFrame('{oops')).toEqual({ ok: false, reason: 'invalid JSON' });
    expect(decodeFrame('[1,2]')).toEqual({ ok: false, reason: 'not a JSON object' });
    expect(decodeFrame('{"type":3}')).toEqual({ ok: false, reason: 'missing string "type"' });
    expect(decodeFrame(42)).toEqual({ ok: false, reason: 'unsupported frame data' });
    expect(decodeFrame(new ArrayBuffer(1), () => { throw new Error('x'); })).toEqual({ ok: false, reason: 'undecodable bytes' });
  });

  it('wraps unknown types and keeps their resume cursor', () => {
    expect(coerceFrame({ type: 'brand_new', a: 1, seq: 4, stream_id: 's' })).toEqual({
      ok: true,
      frame: { type: 'unknown', original_type: 'brand_new', raw: { type: 'brand_new', a: 1, seq: 4, stream_id: 's' }, seq: 4, stream_id: 's' },
    });
  });

  it('coerces wrong field types: required → default, optional → removed; invalid cursors are dropped', () => {
    expect(coerceFrame({ type: 'tool_use', tool_use_id: 7, tool_name: null, tool_input: [], seq: 'x', stream_id: '' })).toEqual({
      ok: true,
      frame: { type: 'tool_use', tool_use_id: '', tool_name: '', tool_input: {} },
    });
    expect(coerceFrame({ type: 'turn_complete', cost: 'free', session_id: null, num_turns: Infinity })).toEqual({
      ok: true,
      frame: { type: 'turn_complete', session_id: null },
    });
    expect(coerceFrame({ type: 'voice_owner_active', active: 'yes' })).toEqual({ ok: true, frame: { type: 'voice_owner_active', active: false } });
    expect(coerceFrame({ type: 'tool_progress', tool_use_id: 'a', elapsed_seconds: 'x', message: 'm' })).toEqual({
      ok: true,
      frame: { type: 'tool_progress', tool_use_id: 'a', elapsed_seconds: 0, message: 'm' },
    });
    expect(coerceFrame({ type: 'models_list', models: {} })).toEqual({ ok: true, frame: { type: 'models_list' } });
    expect(coerceFrame({ type: 'tool_result', tool_use_id: 'a', output: { any: 1 } })).toEqual({
      ok: true,
      frame: { type: 'tool_result', tool_use_id: 'a', output: { any: 1 } },
    });
  });

  it('decodes orchestrator_switch (§6.11a) and coerces its fields', () => {
    const f = { type: 'orchestrator_switch', sdk_session_id: 'PAST', title: 'Trip planning', voice: true, from_session_id: 'O1' };
    expect(decodeFrame(binaryFrame(f))).toEqual({ ok: true, frame: f });
    expect(coerceFrame({ type: 'orchestrator_switch', sdk_session_id: 5, title: 7, voice: 'yes', from_session_id: null })).toEqual({
      ok: true,
      frame: { type: 'orchestrator_switch', sdk_session_id: '', from_session_id: null },
    });
  });

  it('validates session_started.resume_state', () => {
    const ok = { type: 'session_started', session_id: 'L', resume_state: { stream_id: 's', next_seq: 3 } };
    expect(coerceFrame(ok)).toEqual({ ok: true, frame: ok });
    expect(coerceFrame({ type: 'session_started', session_id: 'L', resume_state: { stream_id: 's' } })).toEqual({
      ok: true,
      frame: { type: 'session_started', session_id: 'L' },
    });
  });
});

describe('utf8Decode (fallback without TextDecoder)', () => {
  const enc = (s: string) => new TextEncoder().encode(s);
  it.each(['ascii', 'olá', '日本語', '🌍 emoji', ''])('round-trips %j', (s) => {
    expect(utf8Decode(enc(s))).toBe(s);
  });
  it('replaces invalid sequences with U+FFFD', () => {
    expect(utf8Decode(new Uint8Array([0xff, 0x41]))).toBe('\ufffdA');
    expect(utf8Decode(new Uint8Array([0xe0, 0x80, 0x80]))).toBe('\ufffd'); // overlong: one U+FFFD per invalid sequence
    expect(utf8Decode(new Uint8Array([0xed, 0xa0, 0x80]))).toBe('\ufffd'); // surrogate
    expect(utf8Decode(new Uint8Array([0xf4, 0x90, 0x80, 0x80]))).toBe('\ufffd'); // > U+10FFFF
    expect(utf8Decode(new Uint8Array([0xc3]))).toBe('\ufffd'); // truncated
  });
  it('is used by decodeFrame when injected', () => {
    expect(decodeFrame(binaryFrame({ type: 'ping' }), utf8Decode)).toEqual({ ok: true, frame: { type: 'ping' } });
  });
});

describe('client messages', () => {
  it('serialise to a text frame (T-2)', () => {
    expect(encodeClientMessage({ type: 'permission_response', request_id: 'r', decision: 'allow' })).toBe(
      '{"type":"permission_response","request_id":"r","decision":"allow"}',
    );
  });
});
