/** Small "placeholder" marker so screenshots and QA never mistake a slot for the real thing. */
import type { ReactNode } from 'react';
import styles from './placeholders.module.css';

export function SlotNote({ owner, children }: { owner: string; children: ReactNode }) {
  return (
    <p className={styles.note} data-placeholder={owner}>
      {children} · placeholder until {owner}
    </p>
  );
}
