import { describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { Badge } from '../Badge/Badge';
import { Card } from '../Card/Card';
import { CircularProgress } from '../CircularProgress/CircularProgress';
import { Divider } from '../Divider/Divider';
import { EmptyState } from '../EmptyState/EmptyState';
import { LinearProgress } from '../LinearProgress/LinearProgress';
import { STATUS_LABELS, StatusDot, type SessionStatus } from './StatusDot';

describe('StatusDot', () => {
  it('always carries a text alternative (role=img name, or visible text)', async () => {
    const all = Object.keys(STATUS_LABELS) as SessionStatus[];
    const { getByRole, getByText, container } = renderUi(
      <div>
        {all.map((s) => (
          <StatusDot key={s} status={s} />
        ))}
        <StatusDot status="working" showLabel label="Using Bash" />
      </div>,
    );
    for (const s of all) expect(getByRole('img', { name: STATUS_LABELS[s] }).getAttribute('data-status')).toBe(s);
    expect(getByText('Using Bash')).toBeTruthy();
    await expectNoAxeViolations(container);
  });
});

describe('Badge', () => {
  it('count and dot badges: glyph hidden, label for screen readers; max+ and zero hidden', async () => {
    const { container, getByText, queryByText } = renderUi(
      <div>
        <Badge count={3} label="3 sessions need you">
          <span>Chats</span>
        </Badge>
        <Badge count={150} label="150 unread" />
        <Badge count={0} label="none" />
        <Badge dot label="New activity" />
      </div>,
    );
    expect(getByText('3').getAttribute('aria-hidden')).toBe('true');
    expect(getByText('3 sessions need you')).toBeTruthy();
    expect(getByText('99+')).toBeTruthy();
    expect(queryByText('none')).toBeNull();
    expect(getByText('New activity')).toBeTruthy();
    await expectNoAxeViolations(container);
  });
});

describe('progress', () => {
  it('linear and circular expose progressbar semantics (determinate and indeterminate)', async () => {
    const { getByRole, container } = renderUi(
      <div>
        <LinearProgress aria-label="Upload" value={0.42} />
        <LinearProgress aria-label="Todos" value={3 / 7} valueText="3 of 7" />
        <LinearProgress aria-label="Loading" />
        <CircularProgress aria-label="Context used" value={0.62} size={20} thickness={3} tone="warning" />
        <CircularProgress aria-label="Connecting" />
      </div>,
    );
    expect(getByRole('progressbar', { name: 'Upload' }).getAttribute('aria-valuenow')).toBe('42');
    expect(getByRole('progressbar', { name: 'Todos' }).getAttribute('aria-valuetext')).toBe('3 of 7');
    expect(getByRole('progressbar', { name: 'Loading' }).hasAttribute('aria-valuenow')).toBe(false);
    expect(getByRole('progressbar', { name: 'Context used' }).getAttribute('aria-valuenow')).toBe('62');
    expect(getByRole('progressbar', { name: 'Connecting' }).hasAttribute('aria-valuenow')).toBe(false);
    await expectNoAxeViolations(container);
  });
});

describe('Card, Divider, EmptyState', () => {
  it('static cards are containers; actionable cards are buttons or links', async () => {
    const onClick = vi.fn();
    const { user, getByRole, container } = renderUi(
      <div>
        <Card as="section" aria-label="Static">
          text
        </Card>
        <Card onClick={onClick}>
          <span>Open dashboard</span>
        </Card>
        <Card href="/visuals/x.html" target="_blank">
          <span>Open in new tab</span>
        </Card>
        <Card onClick={onClick} disabled>
          <span>Disabled card</span>
        </Card>
      </div>,
    );
    expect(getByRole('region', { name: 'Static' })).toBeTruthy();
    await user.click(getByRole('button', { name: 'Open dashboard' }));
    await user.click(getByRole('button', { name: 'Disabled card' }));
    expect(onClick).toHaveBeenCalledTimes(1);
    expect(getByRole('link', { name: 'Open in new tab' }).getAttribute('rel')).toBe('noopener noreferrer');
    await expectNoAxeViolations(container);
  });

  it('Divider is a separator (or hidden when decorative); EmptyState renders a heading and actions', async () => {
    const { getAllByRole, getByRole, container } = renderUi(
      <div>
        <Divider />
        <Divider orientation="vertical" />
        <Divider decorative />
        <EmptyState icon="forum" title="No conversations yet" description="Start one.">
          <button type="button">New session</button>
        </EmptyState>
      </div>,
    );
    const seps = getAllByRole('separator');
    expect(seps.map((s) => s.getAttribute('aria-orientation'))).toEqual(['horizontal', 'vertical']);
    expect(getByRole('heading', { level: 2, name: 'No conversations yet' })).toBeTruthy();
    expect(getByRole('button', { name: 'New session' })).toBeTruthy();
    await expectNoAxeViolations(container);
  });
});
