/**
 * The AskUserQuestion picker (spec 12 §4.6a). Part of the lazily loaded "rich" chunk.
 *
 * The agent is **blocked** inside the tool call until this is answered (QU-1), so the card is the
 * thing standing between it and the rest of its turn: one tap per question, or type a reply in the
 * composer and the backend answers with that instead (QU-4).
 */
import { memo, useState } from 'react';
import { pendingQuestion, type AgentQuestion, type QuestionItem } from '@/protocol';
import { errorMessage, getSessionRuntime, respondAgentQuestion, SessionRuntime } from '@/services';
import { showSnackbar, useCatalog, useSession } from '@/stores';
import { Button } from '@/ui/controls';
import { InlineCard } from './InlineCard';
import styles from './Cards.module.css';

type Answers = Record<string, string | string[]>;

/** QU-3: a question is answerable once it has a selection (or typed text for a text/number kind). */
function answered(_q: QuestionItem, picked: string | string[] | undefined): boolean {
  if (Array.isArray(picked)) return picked.length > 0;
  return typeof picked === 'string' && picked.trim() !== '';
}

function allAnswered(questions: readonly QuestionItem[], answers: Answers): boolean {
  return questions.every((q) => answered(q, answers[q.question]));
}

/** One question: choice buttons (multi-select toggles), or a field for the text/number kinds. */
function QuestionRow({
  item,
  value,
  onChange,
  disabled,
}: {
  item: QuestionItem;
  value: string | string[] | undefined;
  onChange: (v: string | string[]) => void;
  disabled: boolean;
}) {
  const multi = item.multiSelect === true;
  const typed = item.kind === 'text' || item.kind === 'number';
  const picked = (label: string): boolean =>
    Array.isArray(value) ? value.includes(label) : value === label;
  const toggle = (label: string): void => {
    if (!multi) return onChange(label);
    const now = Array.isArray(value) ? value : [];
    onChange(now.includes(label) ? now.filter((l) => l !== label) : [...now, label]);
  };
  return (
    <div className={styles.question}>
      <p className={styles.questionText}>
        {item.header ? <span className={styles.questionHeader}>{item.header}</span> : null}
        {item.question}
        {multi ? <span className={styles.muted}> (pick any)</span> : null}
      </p>
      {typed ? (
        <input
          className={styles.answerField}
          type={item.kind === 'number' ? 'number' : 'text'}
          inputMode={item.kind === 'number' ? 'numeric' : undefined}
          min={item.min}
          max={item.max}
          disabled={disabled}
          value={typeof value === 'string' ? value : ''}
          aria-label={item.question}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : (
        <div className={styles.options} role={multi ? 'group' : 'radiogroup'} aria-label={item.question}>
          {item.options.map((o) => (
            <Button
              key={o.label}
              variant={picked(o.label) ? 'filled' : 'outlined'}
              className={picked(o.label) ? undefined : styles.onTone}
              disabled={disabled}
              role={multi ? 'checkbox' : 'radio'}
              aria-checked={picked(o.label)}
              title={o.description}
              onClick={() => toggle(o.label)}
            >
              {o.label}
            </Button>
          ))}
        </div>
      )}
      {item.options.find((o) => picked(o.label))?.description ? (
        <p className={styles.muted}>{item.options.find((o) => picked(o.label))?.description}</p>
      ) : null}
    </div>
  );
}

/**
 * QU-1/QU-3: the newest unanswered question of this view. Closes when that `request_id` leaves
 * `pending` — answered here, on another device, by the orchestrator, or expired at the end of the
 * turn (QU-7) — never on a different one.
 */
export const QuestionCard = memo(function QuestionCard({ localId }: { localId: string }) {
  const question = useSession(localId, (s) => pendingQuestion(s.conv));
  const readOnly = useSession(localId, (s) => s.readOnly);
  const [answers, setAnswers] = useState<Answers>({});
  const [sent, setSent] = useState<string | null>(null);
  if (!question) return null;
  const busy = sent === question.request_id || readOnly;
  const ready = allAnswered(question.questions, answers);
  const send = (): void => {
    const rt = getSessionRuntime(localId);
    if (rt instanceof SessionRuntime && rt.respondQuestion(question.request_id, { answers }))
      setSent(question.request_id);
  };
  return (
    <InlineCard
      tone="permission"
      icon="help"
      role="region"
      aria-label="Question from the agent"
      title={question.questions.length > 1 ? 'A few questions' : 'One question'}
      hint="Or just type your answer below"
      actions={
        <Button variant="filled" disabled={busy || !ready} onClick={send}>
          Send answer
        </Button>
      }
    >
      {question.questions.map((item) => (
        <QuestionRow
          key={item.question}
          item={item}
          value={answers[item.question]}
          disabled={busy}
          onChange={(v) => setAnswers((a) => ({ ...a, [item.question]: v }))}
        />
      ))}
    </InlineCard>
  );
});

// ───────────────────────── agent questions (orchestrator view, QU-8) ─────────────────────────

const AgentQuestionCard = memo(function AgentQuestionCard({ question }: { question: AgentQuestion }) {
  const title = useCatalog(
    (c) => c.sessions.items.find((s) => s.local_id === question.localId)?.title ?? 'An agent session',
  );
  const [answers, setAnswers] = useState<Answers>({});
  const [sent, setSent] = useState(false);
  const ready = allAnswered(question.questions, answers);
  const send = (): void => {
    setSent(true);
    respondAgentQuestion(question.localId, question.request_id, { answers }).catch((err: unknown) => {
      setSent(false);
      showSnackbar(errorMessage(err));
    });
  };
  return (
    <InlineCard
      tone="permission"
      icon="help"
      role="region"
      aria-label={`Question from ${title}`}
      title={`${title} is asking`}
      hint="Or tell Archie below"
      actions={
        <Button variant="filled" disabled={sent || !ready} onClick={send}>
          Send answer
        </Button>
      }
    >
      {question.questions.map((item) => (
        <QuestionRow
          key={item.question}
          item={item}
          value={answers[item.question]}
          disabled={sent}
          onChange={(v) => setAnswers((a) => ({ ...a, [item.question]: v }))}
        />
      ))}
    </InlineCard>
  );
});

/** QU-8: the questions agents are blocked on, seen from an orchestrator view. */
export const AgentQuestionCards = memo(function AgentQuestionCards({ localId }: { localId: string }) {
  const questions = useSession(localId, (s) => s.conv.agentQuestions);
  if (questions.length === 0) return null;
  return (
    <>
      {questions.map((q) => (
        <AgentQuestionCard key={`${q.localId}:${q.request_id}`} question={q} />
      ))}
    </>
  );
});
