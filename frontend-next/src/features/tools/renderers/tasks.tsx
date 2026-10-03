/** Task (subagent), TodoWrite checklist, AskUserQuestion. Both Task and TodoWrite are collapsible now (fixes inv02 §6.2). */
import { Markdown } from '@/features/markdown';
import { Icon, cx, visuallyHiddenClass } from '@/ui/primitives';
import { arr, isRecord, str, type ToolInput } from '../format';
import { OutputView } from '../OutputView';
import styles from '../ToolCard.module.css';
import { Fields, PreText, Section } from './parts';
import type { ToolBodyProps } from './types';

function renderMarkdown(text: string, isError: boolean) {
  return isError ? <pre className={`${styles.pre} ${styles.errText}`}>{text}</pre> : <Markdown source={text} />;
}

/** Task (inv02 F-05 TaskBlock): agent type, description, prompt, then the subagent's report. */
export function TaskBody({ block, input, out }: ToolBodyProps) {
  const prompt = str(input, 'prompt');
  return (
    <Section>
      <Fields
        rows={[
          ['Agent', str(input, 'subagent_type')],
          ['Task', str(input, 'description')],
          ['Model', str(input, 'model')],
        ]}
      />
      {prompt ? <PreText>{prompt}</PreText> : null}
      <OutputView block={block} {...out} label="Report" renderOutput={renderMarkdown} />
    </Section>
  );
}

export type TodoStatus = 'pending' | 'in_progress' | 'completed' | 'cancelled';

export interface TodoItem {
  readonly content: string;
  readonly status: TodoStatus;
  readonly activeForm?: string;
}

export function parseTodos(input: ToolInput): TodoItem[] {
  return (arr(input, 'todos') ?? []).filter(isRecord).map((t) => {
    const s = t.status;
    const status: TodoStatus = s === 'in_progress' || s === 'completed' || s === 'cancelled' ? s : 'pending';
    return {
      content: typeof t.content === 'string' ? t.content : '',
      status,
      ...(typeof t.activeForm === 'string' ? { activeForm: t.activeForm } : {}),
    };
  });
}

/** "3 of 5 done" (spec 13 §3.6 progress), "No todos" when empty. */
export function todoProgress(input: ToolInput): string {
  const todos = parseTodos(input);
  if (todos.length === 0) return 'No todos';
  return `${todos.filter((t) => t.status === 'completed').length} of ${todos.length} done`;
}

const TODO_CLASS: Record<TodoStatus, string | undefined> = {
  pending: styles.todoPending,
  in_progress: styles.todoActive,
  completed: styles.todoDone,
  cancelled: styles.todoCancelled,
};

const TODO_STATE: Record<TodoStatus, string> = {
  pending: 'to do',
  in_progress: 'in progress',
  completed: 'done',
  cancelled: 'cancelled',
};

/**
 * TodoWrite checklist (inv02 F-05 TodoWriteBlock): pending ○, in progress ◐ with `activeForm` in
 * bold, completed ✓ dimmed and struck through. The tool's result is shown too (R7).
 */
export function TodoBody({ block, input, out }: ToolBodyProps) {
  const todos = parseTodos(input);
  return (
    <Section>
      {todos.length === 0 ? (
        <p className={styles.dim}>No todos</p>
      ) : (
        <ul className={styles.todos}>
          {todos.map((t, i) => (
            <li key={i} className={cx(styles.todo, TODO_CLASS[t.status])}>
              <span className={styles.todoMark} aria-hidden="true">
                {t.status === 'completed' ? (
                  <Icon name="check_circle" size={16} />
                ) : t.status === 'cancelled' ? (
                  <Icon name="close" size={16} />
                ) : (
                  <span className={cx(styles.ring, t.status === 'in_progress' && styles.ringHalf)} />
                )}
              </span>
              <span className={visuallyHiddenClass}>{`${TODO_STATE[t.status]}: `}</span>
              <span className={styles.todoText}>{t.status === 'in_progress' && t.activeForm ? t.activeForm : t.content}</span>
            </li>
          ))}
        </ul>
      )}
      <OutputView block={block} {...out} />
    </Section>
  );
}

interface Question {
  readonly question: string;
  readonly header?: string;
  readonly options: readonly { label: string; description?: string }[];
  readonly multiSelect: boolean;
}

export function parseQuestions(input: ToolInput): Question[] {
  return (arr(input, 'questions') ?? []).filter(isRecord).map((q) => ({
    question: typeof q.question === 'string' ? q.question : '',
    ...(typeof q.header === 'string' ? { header: q.header } : {}),
    options: (Array.isArray(q.options) ? q.options : []).filter(isRecord).map((o) => ({
      label: typeof o.label === 'string' ? o.label : String(o.label ?? ''),
      ...(typeof o.description === 'string' ? { description: o.description } : {}),
    })),
    multiSelect: q.multiSelect === true,
  }));
}

export function AskUserQuestionBody({ block, input, out }: ToolBodyProps) {
  const questions = parseQuestions(input);
  return (
    <Section>
      {questions.map((q, i) => (
        <div key={i} className={styles.question}>
          <p className={styles.questionText}>
            {q.header ? <span className={styles.questionHeader}>{q.header}</span> : null}
            {q.question}
            {q.multiSelect ? <span className={styles.dim}> (multiple)</span> : null}
          </p>
          {q.options.length > 0 ? (
            <ul className={styles.options}>
              {q.options.map((o, j) => (
                <li key={j}>
                  <span className={styles.optionLabel}>{o.label}</span>
                  {o.description ? <span className={styles.dim}>{` — ${o.description}`}</span> : null}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ))}
      <OutputView block={block} {...out} label="Answer" />
    </Section>
  );
}
