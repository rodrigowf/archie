import { describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { Fab } from '../Fab/Fab';
import { IconButton } from '../IconButton/IconButton';
import { Button } from './Button';

describe('Button', () => {
  it('renders every variant as a native button with its label, and passes axe', async () => {
    const { container, getAllByRole } = renderUi(
      <div>
        {(['filled', 'tonal', 'outlined', 'text', 'elevated'] as const).map((v) => (
          <Button key={v} variant={v} icon="send">
            {v}
          </Button>
        ))}
      </div>,
    );
    const buttons = getAllByRole('button');
    expect(buttons.map((b) => b.textContent)).toEqual(['filled', 'tonal', 'outlined', 'text', 'elevated']);
    expect(buttons.every((b) => b.getAttribute('type') === 'button')).toBe(true);
    expect(buttons[0]?.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    await expectNoAxeViolations(container);
  });

  it('activates with click, Enter and Space', async () => {
    const onClick = vi.fn();
    const { user, getByRole } = renderUi(<Button onClick={onClick}>Save</Button>);
    await user.click(getByRole('button', { name: 'Save' }));
    await user.keyboard('{Enter}');
    await user.keyboard(' ');
    expect(onClick).toHaveBeenCalledTimes(3);
  });

  it('disabled is aria-disabled: focusable but never activates', async () => {
    const onClick = vi.fn();
    const { user, getByRole, container } = renderUi(
      <Button disabled onClick={onClick}>
        Save
      </Button>,
    );
    const b = getByRole('button', { name: 'Save' });
    expect(b.getAttribute('aria-disabled')).toBe('true');
    expect(b.hasAttribute('disabled')).toBe(false);
    await user.tab();
    expect(document.activeElement).toBe(b);
    await user.click(b);
    await user.keyboard('{Enter}');
    expect(onClick).not.toHaveBeenCalled();
    await expectNoAxeViolations(container);
  });

  it('disabled submit buttons do not submit their form', async () => {
    const onSubmit = vi.fn((e: Event) => {
      e.preventDefault();
    });
    const { user, getByRole } = renderUi(
      <form onSubmit={(e) => onSubmit(e.nativeEvent)}>
        <Button type="submit" disabled>
          Send
        </Button>
      </form>,
    );
    await user.click(getByRole('button'));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('loading shows a spinner, sets aria-busy and blocks clicks', async () => {
    const onClick = vi.fn();
    const { user, getByRole } = renderUi(
      <Button loading icon="check" onClick={onClick}>
        Saving
      </Button>,
    );
    const b = getByRole('button', { name: 'Saving' });
    expect(b.getAttribute('aria-busy')).toBe('true');
    expect(b.querySelector('svg')).toBeNull();
    await user.click(b);
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe('IconButton', () => {
  it('is named by aria-label and toggles with aria-pressed', async () => {
    const onClick = vi.fn();
    const { user, getByRole, rerender, container } = renderUi(
      <IconButton icon="mic" selectedIcon="mic_off" aria-label="Mute" selected={false} onClick={onClick} />,
    );
    const b = getByRole('button', { name: 'Mute' });
    expect(b.getAttribute('aria-pressed')).toBe('false');
    expect(b.querySelector('svg')?.getAttribute('data-icon')).toBe('mic');
    await user.click(b);
    expect(onClick).toHaveBeenCalledTimes(1);
    rerender(<IconButton icon="mic" selectedIcon="mic_off" aria-label="Mute" selected onClick={onClick} />);
    expect(b.getAttribute('aria-pressed')).toBe('true');
    expect(b.querySelector('svg')?.getAttribute('data-icon')).toBe('mic_off');
    await expectNoAxeViolations(container);
  });

  it('a plain action has no aria-pressed; disabled never fires', async () => {
    const onClick = vi.fn();
    const { user, getByRole } = renderUi(<IconButton icon="more_vert" aria-label="Menu" disabled onClick={onClick} />);
    const b = getByRole('button', { name: 'Menu' });
    expect(b.hasAttribute('aria-pressed')).toBe(false);
    await user.click(b);
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe('Fab', () => {
  it('icon-only FABs are named by aria-label; extended FABs by their text; works as a menu trigger', async () => {
    const onClick = vi.fn();
    const { user, getByRole, container } = renderUi(
      <div>
        <Fab icon="add" aria-label="New" aria-haspopup="menu" aria-expanded={false} onClick={onClick} />
        <Fab size="large" icon="graphic_eq" aria-label="Talk" />
        <Fab extended icon="add">
          New chat
        </Fab>
      </div>,
    );
    const trigger = getByRole('button', { name: 'New' });
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu');
    await user.click(trigger);
    expect(onClick).toHaveBeenCalledTimes(1);
    expect(getByRole('button', { name: 'New chat' })).toBeTruthy();
    await expectNoAxeViolations(container);
  });
});
