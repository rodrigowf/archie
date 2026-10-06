import { afterEach, describe, expect, it } from 'vitest';
import { exposeDebug, installDebugHandle, isDebugEnabled, parseDebugChannels, resetDebugChannels } from './debug';

afterEach(() => {
  resetDebugChannels();
  delete window.__archie;
});

describe('debug', () => {
  it('parses ?debug channels', () => {
    expect([...parseDebugChannels('?debug=voice')]).toEqual(['voice']);
    expect([...parseDebugChannels('?x=1&debug=voice,WS')]).toEqual(['voice', 'ws']);
    expect([...parseDebugChannels('?debug=1')]).toEqual(['all']);
    expect(parseDebugChannels('?nodebug=1').size).toBe(0);
  });

  it('all enables every channel', () => {
    resetDebugChannels('?debug=all');
    expect(isDebugEnabled('voice')).toBe(true);
    resetDebugChannels('?debug=voice');
    expect(isDebugEnabled('voice')).toBe(true);
    expect(isDebugEnabled('ws')).toBe(false);
  });

  it('installs window.__archie with build info and exposes hooks', () => {
    const handle = installDebugHandle();
    expect(handle).toMatchObject({ target: 'main', version: expect.stringMatching(/^\d+\.\d+\.\d+\+/) });
    const fn = () => 1;
    exposeDebug('setShowConfig', fn);
    expect(window.__archie?.setShowConfig).toBe(fn);
  });
});
