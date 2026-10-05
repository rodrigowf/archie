/** Category → CSS module class that sets the `--tool-*` colour variables from the tokens. */
import type { ToolCategory } from '@tokens/tokens';
import styles from './ToolCard.module.css';

export const CATEGORY_CLASS: Readonly<Record<ToolCategory, string | undefined>> = {
  read: styles.kRead,
  write: styles.kWrite,
  execute: styles.kExecute,
  script: styles.kScript,
  navigate: styles.kNavigate,
  capture: styles.kCapture,
  interact: styles.kInteract,
  todo: styles.kTodo,
  task: styles.kTask,
  system: styles.kSystem,
  agent: styles.kAgent,
  search: styles.kSearch,
};
