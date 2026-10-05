/** Gallery board chrome (mockups `.board`): title, note, specimens. Dev-only. */
import type { ReactNode } from 'react';
import { Cluster, cx, type SpaceKey } from '@/ui/primitives';
import s from './gallery.module.css';

export function Boards({ children }: { children: ReactNode }) {
  return <div className={s.boards}>{children}</div>;
}

export function Board({ title, note, wide, children }: { title: string; note?: string; wide?: boolean; children: ReactNode }) {
  return (
    <div className={cx(s.board, wide && s.wide)}>
      <h3 className={s.boardTitle}>
        {title}
        {note ? <small>{note}</small> : null}
      </h3>
      {children}
    </div>
  );
}

/** A wrapping row of specimens (mockups `.spec`: 12 dp spacing). */
export function Spec({ children, space = '3' }: { children: ReactNode; space?: SpaceKey }) {
  return <Cluster space={space}>{children}</Cluster>;
}

/** A labelled row: state name, then specimens. */
export function StateRow({ label, children, space = '3' }: { label: string; children: ReactNode; space?: SpaceKey }) {
  return (
    <div className={s.stateRow}>
      <span className={s.specLabel}>{label}</span>
      <Cluster space={space}>{children}</Cluster>
    </div>
  );
}

export function Note({ children }: { children: ReactNode }) {
  return <p className={s.note}>{children}</p>;
}

export const FORCED = ['hover', 'focus', 'pressed'] as const;
