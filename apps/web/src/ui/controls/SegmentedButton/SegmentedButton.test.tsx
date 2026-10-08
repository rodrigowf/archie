import { waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { SegmentedButton, type SegmentOption } from './SegmentedButton';

const THEMES: SegmentOption[] = [
  { value: 'system', label: 'System', icon: 'brightness_auto' },
  { value: 'dark', label: 'Dark', icon: 'dark_mode' },
  { value: 'muted', label: 'Muted', disabled: true },
  { value: 'light', label: 'Light', icon: 'light_mode' },
];

describe('SegmentedButton (single)', () => {
  it('is a radiogroup of radios with one tab stop on the selected segment', async () => {
    const { user, getByRole, getAllByRole, container } = renderUi(<SegmentedButton aria-label="Theme" options={THEMES} defaultValue="dark" />);
    expect(getByRole('radiogroup', { name: 'Theme' })).toBeTruthy();
    const radios = getAllByRole('radio');
    expect(radios.map((r) => r.getAttribute('tabindex'))).toEqual(['-1', '0', '-1', '-1']);
    expect(getByRole('radio', { name: 'Dark' }).getAttribute('aria-checked')).toBe('true');
    // The selected segment shows a check instead of its icon.
    expect(getByRole('radio', { name: 'Dark' }).querySelector('svg')?.getAttribute('data-icon')).toBe('check');
    await user.tab();
    expect(document.activeElement).toBe(getByRole('radio', { name: 'Dark' }));
    await expectNoAxeViolations(container);
  });

  it('arrow keys move and select, skipping disabled; Home/End jump; wraps', async () => {
    const onChange = vi.fn();
    const { user, getByRole } = renderUi(<SegmentedButton aria-label="Theme" options={THEMES} defaultValue="dark" onChange={onChange} />);
    await user.tab();
    await user.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(getByRole('radio', { name: 'Light' }));
    expect(onChange).toHaveBeenLastCalledWith('light');
    await user.keyboard('{ArrowRight}');
    expect(onChange).toHaveBeenLastCalledWith('system');
    await user.keyboard('{End}');
    expect(onChange).toHaveBeenLastCalledWith('light');
    await user.keyboard('{Home}');
    expect(onChange).toHaveBeenLastCalledWith('system');
    await user.keyboard('{ArrowLeft}');
    expect(onChange).toHaveBeenLastCalledWith('light');
  });

  it('click selects; a disabled segment does not', async () => {
    const onChange = vi.fn();
    const { user, getByRole } = renderUi(<SegmentedButton aria-label="Theme" options={THEMES} onChange={onChange} />);
    await user.click(getByRole('radio', { name: 'Muted' }));
    expect(onChange).not.toHaveBeenCalled();
    await user.click(getByRole('radio', { name: 'System' }));
    expect(onChange).toHaveBeenCalledWith('system');
  });
});

describe('SegmentedButton (multiple)', () => {
  it('is a group of aria-pressed toggles, each a tab stop', async () => {
    const onChange = vi.fn();
    const { user, getByRole, getAllByRole, container } = renderUi(
      <SegmentedButton
        multiple
        aria-label="Engines"
        defaultValue={['vosk']}
        onChange={onChange}
        options={[
          { value: 'vosk', label: 'Vosk' },
          { value: 'whisper', label: 'Whisper' },
        ]}
      />,
    );
    expect(getByRole('group', { name: 'Engines' })).toBeTruthy();
    const [vosk, whisper] = getAllByRole('button');
    expect(vosk?.getAttribute('aria-pressed')).toBe('true');
    expect(whisper?.getAttribute('aria-pressed')).toBe('false');
    await user.tab();
    await user.tab();
    expect(document.activeElement).toBe(whisper);
    await user.keyboard(' ');
    expect(onChange).toHaveBeenLastCalledWith(['vosk', 'whisper']);
    await user.click(vosk as HTMLElement);
    expect(onChange).toHaveBeenLastCalledWith(['whisper']);
    await expectNoAxeViolations(container);
  });
});

describe('SegmentedButton (dot, title, wrap)', () => {
  it('a dotted segment keeps its label as the name and shows its title', () => {
    const { getByRole } = renderUi(
      <SegmentedButton aria-label="Effort" options={[{ value: 'low', label: 'Low' }, { value: 'high', label: 'High', dot: true, title: 'CLI default' }]} defaultValue="low" />,
    );
    const high = getByRole('radio', { name: 'High' });
    expect(high.getAttribute('title')).toBe('CLI default');
    expect(high.querySelector('[aria-hidden="true"]:not(svg)')).toBeTruthy();
  });

  it('wrap: separate pills (class on the group); auto stays one row when nothing is cut', () => {
    const opts: SegmentOption[] = [
      { value: 'a', label: 'Alpha' },
      { value: 'b', label: 'Beta' },
    ];
    const { getAllByRole, rerender } = renderUi(<SegmentedButton aria-label="Wrap" options={opts} wrap />);
    const wrapped = (getAllByRole('radiogroup')[0] as HTMLElement).className;
    rerender(<SegmentedButton aria-label="Wrap" options={opts} wrap="auto" fullWidth />);
    // jsdom has no layout: nothing overflows, so "auto" keeps the one-row (full width) look
    const auto = (getAllByRole('radiogroup')[0] as HTMLElement).className;
    expect(auto).not.toBe(wrapped);
    expect(auto).toContain('fullWidth');
  });

  it('wrap="auto" switches to pills when the labels need more than the row', async () => {
    const sw = vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return this.getAttribute('role') === 'radiogroup' ? 100 : 80;
    });
    const cw = vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return this.getAttribute('role') === 'radiogroup' ? 100 : 40;
    });
    // a ResizeObserver that reports at once (the test setup's stub never does)
    class FiringObserver {
      constructor(private readonly cb: () => void) {}
      observe(): void {
        this.cb();
      }
      disconnect(): void {}
    }
    vi.stubGlobal('ResizeObserver', FiringObserver);
    try {
      const opts: SegmentOption[] = [
        { value: 'a', label: 'A rather long label' },
        { value: 'b', label: 'Another long label' },
      ];
      const { getByRole } = renderUi(<SegmentedButton aria-label="Auto" options={opts} wrap="auto" fullWidth />);
      const one = renderUi(<SegmentedButton aria-label="Pills" options={opts} wrap />);
      const pills = one.getByRole('radiogroup', { name: 'Pills' }).className;
      await waitFor(() => expect(getByRole('radiogroup', { name: 'Auto' }).className).toBe(pills));
    } finally {
      vi.unstubAllGlobals();
      sw.mockRestore();
      cw.mockRestore();
    }
  });
});
