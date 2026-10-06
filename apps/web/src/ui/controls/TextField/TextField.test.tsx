import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { IconButton } from '../IconButton/IconButton';
import { SearchField } from '../SearchField/SearchField';
import { TextField } from './TextField';

describe('TextField', () => {
  it('labels the input, describes it with the supporting text, and passes axe (outlined and filled)', async () => {
    const { container, getByLabelText } = renderUi(
      <div>
        <TextField label="Server address" supportingText="Port 8765 is added for you" />
        <TextField variant="filled" label="Wake phrase" defaultValue="Hey Archie" />
      </div>,
    );
    const input = getByLabelText('Server address') as HTMLInputElement;
    const help = document.getElementById(input.getAttribute('aria-describedby') ?? '');
    expect(help?.textContent).toBe('Port 8765 is added for you');
    expect((getByLabelText('Wake phrase') as HTMLInputElement).value).toBe('Hey Archie');
    await expectNoAxeViolations(container);
  });

  it('floats the label on focus and when populated', async () => {
    const { user, getByLabelText, container } = renderUi(<TextField label="Name" />);
    const root = container.firstElementChild as HTMLElement;
    const floated = (): boolean => root.className.includes('floated');
    expect(floated()).toBe(false);
    await user.click(getByLabelText('Name'));
    expect(floated()).toBe(true);
    await user.keyboard('Archie');
    await user.tab();
    expect(floated()).toBe(true);
    await user.clear(getByLabelText('Name'));
    await user.tab();
    expect(floated()).toBe(false);
  });

  it('error: aria-invalid and the message replaces the supporting text', async () => {
    const { getByLabelText, getByText, queryByText, container } = renderUi(
      <TextField label="SSH host" supportingText="host:port" error="Port must be a number" defaultValue="x:2a" />,
    );
    const input = getByLabelText('SSH host');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(getByText('Port must be a number').id).toBe(input.getAttribute('aria-describedby'));
    expect(queryByText('host:port')).toBeNull();
    await expectNoAxeViolations(container);
  });

  it('controlled value with onValueChange', async () => {
    const seen = vi.fn();
    function Demo() {
      const [v, setV] = useState('');
      return (
        <TextField
          label="Port"
          value={v}
          onValueChange={(x) => {
            seen(x);
            setV(x.replace(/\D/g, ''));
          }}
        />
      );
    }
    const { user, getByLabelText } = renderUi(<Demo />);
    await user.type(getByLabelText('Port'), '8a7');
    expect((getByLabelText('Port') as HTMLInputElement).value).toBe('87');
    expect(seen).toHaveBeenLastCalledWith('87');
  });

  it('always renders 16 px text (iOS focus zoom rule) and a trailing node outside the input', async () => {
    const { getByLabelText, getByRole } = renderUi(
      <TextField label="Key" trailing={<IconButton icon="visibility" aria-label="Show key" />} />,
    );
    expect(getByLabelText('Key').className).toMatch(/input/);
    expect(getByRole('button', { name: 'Show key' }).closest('input')).toBeNull();
  });

  it('multiline renders a textarea that grows with its content', async () => {
    const { user, getByLabelText } = renderUi(<TextField label="Prompt" multiline maxRows={4} />);
    const ta = getByLabelText('Prompt') as HTMLTextAreaElement;
    expect(ta.tagName).toBe('TEXTAREA');
    Object.defineProperty(ta, 'scrollHeight', { configurable: true, get: () => 80 });
    await user.type(ta, 'a{Shift>}{Enter}{/Shift}b');
    expect(ta.style.height).toBe('80px');
    expect(ta.value).toBe('a\nb');
  });

  it('disabled fields are not editable', async () => {
    const { user, getByLabelText } = renderUi(<TextField label="Locked" defaultValue="x" disabled />);
    const input = getByLabelText('Locked') as HTMLInputElement;
    await user.type(input, 'y');
    expect(input.value).toBe('x');
    expect(input.disabled).toBe(true);
  });
});

describe('SearchField', () => {
  it('is a labelled searchbox; the clear button and Escape empty it; Enter submits', async () => {
    const onSubmit = vi.fn();
    const outer = vi.fn();
    const { user, getByRole, queryByRole, container } = renderUi(
      // eslint-disable-next-line jsx-a11y/no-static-element-interactions
      <div onKeyDown={(e) => outer(e.key)}>
        <SearchField label="Search conversations" onSubmit={onSubmit} />
      </div>,
    );
    const box = getByRole('searchbox', { name: 'Search conversations' }) as HTMLInputElement;
    expect(queryByRole('button', { name: 'Clear search' })).toBeNull();
    await user.type(box, 'voice{Enter}');
    expect(onSubmit).toHaveBeenCalledWith('voice');
    await user.click(getByRole('button', { name: 'Clear search' }));
    expect(box.value).toBe('');
    expect(document.activeElement).toBe(box);
    await user.type(box, 'tv');
    outer.mockClear();
    await user.keyboard('{Escape}');
    expect(box.value).toBe('');
    expect(outer).not.toHaveBeenCalledWith('Escape');
    await user.keyboard('{Escape}');
    expect(outer).toHaveBeenCalledWith('Escape');
    await expectNoAxeViolations(container);
  });
});
