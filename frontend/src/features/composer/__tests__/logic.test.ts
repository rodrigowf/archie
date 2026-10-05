import { describe, expect, it } from 'vitest';
import { formatClock, isSubmitKey, placeholderFor, primaryMode, ringTone, withSlashCommand } from '../logic';

describe('primaryMode (IA §6: Voice → Send → Stop)', () => {
  const base = { kind: 'orchestrator' as const, hasText: false, working: false, voiceAvailable: true };
  it('Archie, empty, idle → Voice', () => expect(primaryMode(base)).toBe('voice'));
  it('any text → Send (also while working: Enter queues)', () => {
    expect(primaryMode({ ...base, hasText: true })).toBe('send');
    expect(primaryMode({ ...base, hasText: true, working: true })).toBe('send');
    expect(primaryMode({ ...base, kind: 'agent', hasText: true, working: true })).toBe('send');
  });
  it('working and empty → Stop', () => {
    expect(primaryMode({ ...base, working: true })).toBe('stop');
    expect(primaryMode({ ...base, kind: 'agent', working: true })).toBe('stop');
  });
  it('agent, empty, idle → disabled Send (no voice for agents)', () => expect(primaryMode({ ...base, kind: 'agent' })).toBe('send-disabled'));
  it('Archie without voice capability → disabled Send', () => expect(primaryMode({ ...base, voiceAvailable: false })).toBe('send-disabled'));
});

describe('context ring (spec 12 §6.4: caution ≥ 50 %, warning ≥ 80 %)', () => {
  it('maps levels to tones', () => {
    expect(ringTone({ percent: 49, level: 'normal' })).toBe('primary');
    expect(ringTone({ percent: 50, level: 'caution' })).toBe('warning');
    expect(ringTone({ percent: 80, level: 'warning' })).toBe('error');
    expect(ringTone({ percent: null, level: 'normal' })).toBe('primary');
  });
});

describe('copy and keys', () => {
  it('placeholder says where the text goes', () => {
    expect(placeholderFor({ kind: 'orchestrator', title: '', working: false, permissionPending: false })).toBe('Message Archie…');
    expect(placeholderFor({ kind: 'agent', title: 'Refactor voice module', working: false, permissionPending: false })).toBe('Message Refactor voice module…');
    expect(placeholderFor({ kind: 'agent', title: '', working: false, permissionPending: false })).toBe('Message the agent…');
    expect(placeholderFor({ kind: 'agent', title: 'Run the stall check on the forecast script', working: false, permissionPending: false })).toBe(
      'Message Run the stall check on…',
    );
    expect(placeholderFor({ kind: 'agent', title: 'x', working: true, permissionPending: false })).toBe('Queue a message…');
    expect(placeholderFor({ kind: 'agent', title: 'x', working: true, permissionPending: true })).toBe('Type to give feedback…');
  });
  it('Enter submits; Shift+Enter and IME composition do not', () => {
    expect(isSubmitKey({ key: 'Enter', shiftKey: false })).toBe(true);
    expect(isSubmitKey({ key: 'Enter', shiftKey: true })).toBe(false);
    expect(isSubmitKey({ key: 'Enter', shiftKey: false, isComposing: true })).toBe(false);
    expect(isSubmitKey({ key: 'Enter', shiftKey: false, keyCode: 229 })).toBe(false);
    expect(isSubmitKey({ key: 'a', shiftKey: false })).toBe(false);
  });
  it('formats the recording clock', () => {
    expect(formatClock(0)).toBe('0:00');
    expect(formatClock(7_900)).toBe('0:07');
    expect(formatClock(60_000)).toBe('1:00');
  });
  it('puts a slash command first, replacing an earlier one', () => {
    expect(withSlashCommand('', 'recall')).toBe('/recall ');
    expect(withSlashCommand('find the TV notes', 'recall')).toBe('/recall find the TV notes');
    expect(withSlashCommand('/create-viz energy', 'recall')).toBe('/recall energy');
  });
});
