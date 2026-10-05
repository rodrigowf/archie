import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { Select, prefersNativeSelect, type SelectOption } from './Select';

const OPTIONS: SelectOption[] = [
  { value: 'a', label: 'Alpha' },
  { value: 'b', label: 'Bravo' },
  { value: 'c', label: 'Charlie', disabled: true },
  { value: 'd', label: 'Delta' },
];

function stubCoarsePointer(coarse: boolean): void {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) =>
      ({
        matches: query === '(pointer: coarse)' ? coarse : false,
        media: query,
        onchange: null,
        addListener: () => undefined,
        removeListener: () => undefined,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        dispatchEvent: () => false,
      }) as MediaQueryList,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Select mode', () => {
  it('is native on touch (pointer: coarse) and a menu on fine pointers', () => {
    stubCoarsePointer(true);
    expect(prefersNativeSelect()).toBe(true);
    const touch = renderUi(<Select label="Model" options={OPTIONS} />);
    expect(touch.container.querySelector('select')).not.toBeNull();
    touch.unmount();
    stubCoarsePointer(false);
    expect(prefersNativeSelect()).toBe(false);
    const fine = renderUi(<Select label="Model" options={OPTIONS} />);
    expect(fine.container.querySelector('select')).toBeNull();
    expect(fine.getByRole('combobox', { name: 'Model' })).toBeTruthy();
  });
});

describe('Select (native)', () => {
  it('is a labelled <select> and reports changes', async () => {
    const onChange = vi.fn();
    const { user, getByLabelText, container } = renderUi(
      <Select native label="Model" options={OPTIONS} defaultValue="a" onChange={onChange} supportingText="Pick one" />,
    );
    const select = getByLabelText('Model') as HTMLSelectElement;
    expect(select.tagName).toBe('SELECT');
    await user.selectOptions(select, 'd');
    expect(onChange).toHaveBeenCalledWith('d');
    expect((select.querySelector('option[value="c"]') as HTMLOptionElement).disabled).toBe(true);
    await expectNoAxeViolations(container);
  });
});

describe('Select (menu)', () => {
  it('follows the select-only combobox pattern and passes axe open and closed', async () => {
    const { user, getByRole, container } = renderUi(<Select native={false} label="Model" options={OPTIONS} defaultValue="b" />);
    const combo = getByRole('combobox', { name: 'Model' });
    expect(combo.getAttribute('aria-expanded')).toBe('false');
    expect(combo.textContent).toBe('Bravo');
    await expectNoAxeViolations(container);
    await user.click(combo);
    expect(combo.getAttribute('aria-expanded')).toBe('true');
    const list = getByRole('listbox', { name: 'Model' });
    expect(combo.getAttribute('aria-controls')).toBe(list.id);
    expect(document.getElementById(combo.getAttribute('aria-activedescendant') ?? '')?.textContent).toBe('Bravo');
    expect(getByRole('option', { name: 'Bravo' }).getAttribute('aria-selected')).toBe('true');
    await expectNoAxeViolations(container);
  });

  it('keyboard: arrows skip disabled options, Enter chooses, Escape closes without choosing', async () => {
    const onChange = vi.fn();
    const { user, getByRole } = renderUi(<Select native={false} label="Model" options={OPTIONS} defaultValue="b" onChange={onChange} />);
    const combo = getByRole('combobox');
    const active = (): string | undefined => document.getElementById(combo.getAttribute('aria-activedescendant') ?? '')?.textContent ?? undefined;
    await user.tab();
    expect(document.activeElement).toBe(combo);
    await user.keyboard('{ArrowDown}');
    expect(combo.getAttribute('aria-expanded')).toBe('true');
    expect(active()).toBe('Bravo');
    await user.keyboard('{ArrowDown}');
    expect(active()).toBe('Delta'); // Charlie is disabled
    await user.keyboard('{Home}');
    expect(active()).toBe('Alpha');
    await user.keyboard('{End}');
    expect(active()).toBe('Delta');
    await user.keyboard('{Escape}');
    expect(combo.getAttribute('aria-expanded')).toBe('false');
    expect(onChange).not.toHaveBeenCalled();
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}');
    expect(onChange).toHaveBeenCalledWith('d');
    expect(combo.textContent).toBe('Delta');
    expect(document.activeElement).toBe(combo);
  });

  it('Escape inside the popup does not reach outer handlers (closes only the popup)', async () => {
    const outer = vi.fn();
    const { user, getByRole } = renderUi(
      // eslint-disable-next-line jsx-a11y/no-static-element-interactions
      <div onKeyDown={(e) => outer(e.key)}>
        <Select native={false} label="Model" options={OPTIONS} />
      </div>,
    );
    await user.click(getByRole('combobox'));
    await user.keyboard('{Escape}');
    expect(outer).not.toHaveBeenCalledWith('Escape');
  });

  it('type-ahead jumps to the matching option', async () => {
    const onChange = vi.fn();
    const { user, getByRole } = renderUi(<Select native={false} label="Model" options={OPTIONS} onChange={onChange} />);
    await user.click(getByRole('combobox'));
    await user.keyboard('d{Enter}');
    expect(onChange).toHaveBeenCalledWith('d');
  });

  it('mouse: choosing an option keeps focus on the combobox; outside press closes', async () => {
    const onChange = vi.fn();
    const { user, getByRole } = renderUi(
      <div>
        <Select native={false} label="Model" options={OPTIONS} onChange={onChange} />
        <p>outside</p>
      </div>,
    );
    const combo = getByRole('combobox');
    await user.click(combo);
    await user.click(getByRole('option', { name: 'Alpha' }));
    expect(onChange).toHaveBeenCalledWith('a');
    expect(combo.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(combo);
    await user.click(combo);
    expect(combo.getAttribute('aria-expanded')).toBe('true');
    await user.click(getByRole('option', { name: 'Charlie' }));
    expect(onChange).toHaveBeenCalledTimes(1); // disabled option ignored
    await user.click(document.querySelector('p') as HTMLElement);
    expect(combo.getAttribute('aria-expanded')).toBe('false');
  });

  it('disabled does not open', async () => {
    const { user, getByRole } = renderUi(<Select native={false} label="Model" options={OPTIONS} disabled />);
    const combo = getByRole('combobox');
    expect(combo.getAttribute('aria-disabled')).toBe('true');
    await user.click(combo);
    expect(combo.getAttribute('aria-expanded')).toBe('false');
  });
});
