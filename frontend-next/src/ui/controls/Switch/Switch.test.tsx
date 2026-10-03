import { describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { Checkbox } from '../Checkbox/Checkbox';
import { Radio, RadioGroup } from '../Radio/Radio';
import { Switch } from './Switch';

// user-event walks radio groups with CSS.escape, which jsdom lacks (a shim for src/test/setup.ts, W-01).
const css = ((globalThis as { CSS?: { escape?: (s: string) => string } }).CSS ??= {});
css.escape ??= (s: string) => s.replace(/([^\w-])/g, '\\$1');

describe('Switch', () => {
  it('is role=switch with aria-checked, toggled by click, Space and Enter', async () => {
    const onChange = vi.fn();
    const { user, getByRole, container } = renderUi(<Switch aria-label="Wake word" onCheckedChange={onChange} />);
    const sw = getByRole('switch', { name: 'Wake word' });
    expect(sw.getAttribute('aria-checked')).toBe('false');
    await user.click(sw);
    expect(sw.getAttribute('aria-checked')).toBe('true');
    await user.keyboard(' ');
    expect(sw.getAttribute('aria-checked')).toBe('false');
    await user.keyboard('{Enter}');
    expect(onChange.mock.calls.map((c) => c[0] as boolean)).toEqual([true, false, true]);
    await expectNoAxeViolations(container);
  });

  it('a visible label names it and toggles it', async () => {
    const { user, getByRole, getByText } = renderUi(<Switch label="Reduce motion" />);
    const sw = getByRole('switch', { name: 'Reduce motion' });
    await user.click(getByText('Reduce motion'));
    expect(sw.getAttribute('aria-checked')).toBe('true');
  });

  it('disabled is aria-disabled and never toggles', async () => {
    const onChange = vi.fn();
    const { user, getByRole } = renderUi(<Switch aria-label="X" checked disabled onCheckedChange={onChange} />);
    const sw = getByRole('switch');
    expect(sw.getAttribute('aria-disabled')).toBe('true');
    await user.click(sw);
    await user.keyboard(' ');
    expect(onChange).not.toHaveBeenCalled();
    expect(sw.getAttribute('aria-checked')).toBe('true');
  });
});

describe('Checkbox', () => {
  it('is a native checkbox with a label; Space toggles; indeterminate is mixed', async () => {
    const onChange = vi.fn();
    const { user, getByRole, container } = renderUi(
      <div>
        <Checkbox label="Enabled" onCheckedChange={onChange} />
        <Checkbox aria-label="All rows" indeterminate />
      </div>,
    );
    const cb = getByRole('checkbox', { name: 'Enabled' }) as HTMLInputElement;
    await user.click(cb);
    expect(cb.checked).toBe(true);
    await user.keyboard(' ');
    expect(onChange.mock.calls.map((c) => c[0] as boolean)).toEqual([true, false]);
    expect((getByRole('checkbox', { name: 'All rows' }) as HTMLInputElement).indeterminate).toBe(true);
    await expectNoAxeViolations(container);
  });

  it('error sets aria-invalid; disabled blocks changes', async () => {
    const { user, getByRole } = renderUi(
      <div>
        <Checkbox aria-label="Bad" error />
        <Checkbox aria-label="Off" disabled />
      </div>,
    );
    expect(getByRole('checkbox', { name: 'Bad' }).getAttribute('aria-invalid')).toBe('true');
    const off = getByRole('checkbox', { name: 'Off' }) as HTMLInputElement;
    await user.click(off);
    expect(off.checked).toBe(false);
  });
});

describe('RadioGroup', () => {
  it('is a labelled radiogroup with one tab stop; arrow keys move and select', async () => {
    const onChange = vi.fn();
    const { user, getByRole, container } = renderUi(
      <RadioGroup label="Theme" defaultValue="dark" onChange={onChange}>
        <Radio value="system" label="System" />
        <Radio value="dark" label="Dark" />
        <Radio value="light" label="Light" />
      </RadioGroup>,
    );
    expect(getByRole('radiogroup', { name: 'Theme' })).toBeTruthy();
    const dark = getByRole('radio', { name: 'Dark' }) as HTMLInputElement;
    expect(dark.checked).toBe(true);
    await user.tab();
    expect(document.activeElement).toBe(dark);
    await user.keyboard('{ArrowDown}');
    const light = getByRole('radio', { name: 'Light' }) as HTMLInputElement;
    expect(document.activeElement).toBe(light);
    expect(light.checked).toBe(true);
    expect(onChange).toHaveBeenLastCalledWith('light');
    await user.click(getByRole('radio', { name: 'System' }));
    expect(onChange).toHaveBeenLastCalledWith('system');
    await expectNoAxeViolations(container);
  });

  it('a disabled group disables every radio', () => {
    const { getAllByRole } = renderUi(
      <RadioGroup aria-label="Mode" disabled>
        <Radio value="a" label="A" />
        <Radio value="b" label="B" />
      </RadioGroup>,
    );
    expect(getAllByRole('radio').every((r) => (r as HTMLInputElement).disabled)).toBe(true);
  });
});
