/**
 * Orchestrator conversation-history tools: search_history (sessions with matching excerpts) and
 * read_conversation (a window of turns). Both outputs are JSON; they are parsed from the full
 * `block.output` (the output region's text is capped at 20 KB, which would cut the JSON) and fall
 * back to the plain text when they don't parse (older result shapes, errors).
 */
import { cx } from '@/ui/primitives';
import { isRecord, num, shortId, str } from '../format';
import { OutputView } from '../OutputView';
import styles from '../ToolCard.module.css';
import { Fields, Section } from './parts';
import type { ToolBodyProps } from './types';

export interface HistoryTurn {
  readonly turn: number;
  readonly role: string;
  readonly date: string | null;
  readonly text: string;
}

export interface HistorySession {
  readonly sessionId: string;
  readonly title: string | null;
  readonly startedAt: string | null;
  readonly relevance: string | null;
  readonly note: string | null;
  readonly copies: number;
  readonly hits: readonly HistoryTurn[];
}

const s = (v: unknown): string | null => (typeof v === 'string' && v !== '' ? v : null);
const day = (iso: string | null): string => (iso ? iso.slice(0, 10) : '');

function parseJson(text: string | null): Record<string, unknown> | null {
  if (!text) return null;
  try {
    const v: unknown = JSON.parse(text);
    return isRecord(v) ? v : null;
  } catch {
    return null;
  }
}

function turnsOf(v: unknown): HistoryTurn[] {
  if (!Array.isArray(v)) return [];
  return v.filter(isRecord).map((t) => ({
    turn: typeof t.turn === 'number' ? t.turn : 0,
    role: s(t.role) ?? '',
    date: s(t.date),
    text: typeof t.text === 'string' ? t.text : '',
  }));
}

/** search_history output → sessions, or null when it isn't the session-grouped shape. */
export function parseHistorySearch(text: string | null): { sessions: HistorySession[]; error: string | null; note: string | null } | null {
  const v = parseJson(text);
  if (!v || !Array.isArray(v.sessions)) return null;
  const sessions = v.sessions.filter(isRecord).map((x) => ({
    sessionId: s(x.session_id) ?? '',
    title: s(x.title),
    startedAt: s(x.started_at),
    relevance: s(x.relevance),
    note: s(x.note),
    copies: Array.isArray(x.copies) ? x.copies.length : 0,
    hits: turnsOf(x.hits),
  }));
  return { sessions, error: s(v.error), note: s(v.note) };
}

/** read_conversation output → title + turns, or null when it doesn't parse. */
export function parseConversationRead(text: string | null): { title: string | null; total: number | null; turns: HistoryTurn[]; error: string | null } | null {
  const v = parseJson(text);
  if (!v || (!Array.isArray(v.turns) && !s(v.error))) return null;
  return {
    title: s(v.title),
    total: typeof v.total_turns === 'number' ? v.total_turns : null,
    turns: turnsOf(v.turns),
    error: s(v.error),
  };
}

function Turn({ t }: { t: HistoryTurn }) {
  return (
    <div className={styles.histTurn}>
      <span className={styles.histTurnHead}>{`#${t.turn} ${t.role}${t.date ? ` · ${t.date.slice(0, 16).replace('T', ' ')}` : ''}`}</span>
      <span className={styles.histText}>{t.text}</span>
    </div>
  );
}

function renderSearch(full: string | null) {
  return (text: string, isError: boolean) => {
    const r = parseHistorySearch(full);
    if (!r) return <pre className={cx(styles.pre, isError && styles.errText)}>{text}</pre>;
    if (r.error) return <span className={styles.errText}>{r.error}</span>;
    if (r.sessions.length === 0) return <span className={styles.dim}>{r.note ?? 'No matching conversations'}</span>;
    return (
      <div className={styles.hist}>
        {r.sessions.map((x) => (
          <div key={x.sessionId} className={cx(styles.histSession, x.relevance === 'weak' && styles.histWeak)}>
            <div className={styles.histHead}>
              <span className={styles.histTitle}>{x.title ?? 'Untitled conversation'}</span>
              <span className={styles.histMeta}>
                {[day(x.startedAt), x.relevance, shortId(x.sessionId), x.copies ? `+${x.copies} ${x.copies === 1 ? 'copy' : 'copies'}` : '']
                  .filter(Boolean)
                  .join(' · ')}
              </span>
            </div>
            {x.note ? <span className={styles.dim}>{x.note}</span> : null}
            {x.hits.map((t) => (
              <Turn key={t.turn} t={t} />
            ))}
          </div>
        ))}
      </div>
    );
  };
}

function renderRead(full: string | null) {
  return (text: string, isError: boolean) => {
    const r = parseConversationRead(full);
    if (!r) return <pre className={cx(styles.pre, isError && styles.errText)}>{text}</pre>;
    if (r.error) return <span className={styles.errText}>{r.error}</span>;
    const first = r.turns[0]?.turn;
    const last = r.turns[r.turns.length - 1]?.turn;
    return (
      <div className={styles.hist}>
        <div className={styles.histHead}>
          <span className={styles.histTitle}>{r.title ?? 'Untitled conversation'}</span>
          <span className={styles.histMeta}>
            {first !== undefined ? `turns ${first}–${last}${r.total !== null ? ` of ${r.total}` : ''}` : 'no turns'}
          </span>
        </div>
        {r.turns.map((t) => (
          <Turn key={t.turn} t={t} />
        ))}
      </div>
    );
  };
}

export function SearchHistoryBody({ block, input, out }: ToolBodyProps) {
  return (
    <Section>
      <Fields
        rows={[
          ['Query', str(input, 'query')],
          ['Max', str(input, 'max_results')],
          ['After', str(input, 'after')],
          ['Before', str(input, 'before')],
        ]}
      />
      <OutputView block={block} {...out} renderOutput={renderSearch(block.output)} />
    </Section>
  );
}

export function ReadConversationBody({ block, input, out }: ToolBodyProps) {
  const turn = num(input, 'turn');
  const before = num(input, 'before');
  const after = num(input, 'after');
  return (
    <Section>
      <Fields
        rows={[
          ['Session', str(input, 'session_id'), true],
          ['Turn', turn !== undefined ? String(turn) : undefined],
          ['Window', before !== undefined || after !== undefined ? `${before ?? 3} before · ${after ?? 6} after` : undefined],
        ]}
      />
      <OutputView block={block} {...out} renderOutput={renderRead(block.output)} />
    </Section>
  );
}

/** Header summary: `session 1a2b3c4d · turn 40`. */
export function readConversationSummary(input: Record<string, unknown>): string {
  const id = typeof input.session_id === 'string' ? input.session_id.trim() : '';
  const turn = typeof input.turn === 'number' ? input.turn : undefined;
  return [id ? `session ${shortId(id)}` : '', turn !== undefined ? `turn ${turn}` : ''].filter(Boolean).join(' · ');
}
