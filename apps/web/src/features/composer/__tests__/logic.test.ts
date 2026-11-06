import { describe, expect, it } from 'vitest';
import { canDictate, dictatedDraft, formatClock, isSubmitKey, placeholderFor, primaryMode, ringTone, withSlashCommand } from '../logic';

describe('primaryMode (IA §6: Voice → Dictate → Send → Stop)', () => {
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
  it('agent, empty, idle, no dictation → disabled Send (no voice for agents)', () =>
    expect(primaryMode({ ...base, kind: 'agent' })).toBe('send-disabled'));
  it('Archie without voice capability → disabled Send', () => expect(primaryMode({ ...base, voiceAvailable: false })).toBe('send-disabled'));

  describe('dictate', () => {
    it('agent, empty, idle, dictation on → Dictate (the dead disabled Send is gone)', () =>
      expect(primaryMode({ ...base, kind: 'agent', dictationAvailable: true })).toBe('dictate'));
    it('Archie keeps Voice ahead of Dictate: speaking to Archie is that tab’s point', () =>
      expect(primaryMode({ ...base, dictationAvailable: true })).toBe('voice'));
    it('Archie with voice impossible falls to Dictate rather than a dead button', () =>
      expect(primaryMode({ ...base, voiceAvailable: false, dictationAvailable: true })).toBe('dictate'));
    it('text and working still win over it', () => {
      expect(primaryMode({ ...base, kind: 'agent', dictationAvailable: true, hasText: true })).toBe('send');
      expect(primaryMode({ ...base, kind: 'agent', dictationAvailable: true, working: true })).toBe('stop');
    });
  });
});

describe('canDictate (reads the detected capabilities, so ?caps=compat is honoured)', () => {
  const able = { getUserMedia: true, webAudio: true, audioWorklet: true };
  it('needs a microphone, Web Audio and an AudioWorklet', () => {
    expect(canDictate(able)).toBe(true);
    expect(canDictate(null)).toBe(false);
    expect(canDictate({ ...able, getUserMedia: false })).toBe(false);
    expect(canDictate({ ...able, webAudio: false })).toBe(false);
    // Safari 12 and the compat simulation: record-then-transcribe instead of streaming.
    expect(canDictate({ ...able, audioWorklet: false })).toBe(false);
  });
});

describe('dictatedDraft (dictation appends to what is already typed)', () => {
  it('exactly one space between the halves, whatever each side carries', () => {
    // OpenAI transcripts open with a leading space; ElevenLabs ones do not.
    expect(dictatedDraft('Check the', ' composer')).toBe('Check the composer');
    expect(dictatedDraft('Check the ', 'composer')).toBe('Check the composer');
    expect(dictatedDraft('Check the\n', '  composer')).toBe('Check the composer');
  });
  it('either half alone is returned as-is', () => {
    expect(dictatedDraft('', ' just dictated')).toBe('just dictated');
    expect(dictatedDraft('just typed ', '')).toBe('just typed');
    expect(dictatedDraft('', '')).toBe('');
  });
  it('keeps internal whitespace of the dictated text', () => {
    expect(dictatedDraft('One.', ' Two. Three.')).toBe('One. Two. Three.');
  });
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
    expect(placeholderFor({ kind: 'agent', title: 'x', working: true, permissionPending: false, steers: true })).toBe('Add to the running turn…');
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
