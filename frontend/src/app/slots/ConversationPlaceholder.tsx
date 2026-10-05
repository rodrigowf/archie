/**
 * PLACEHOLDER (W-09 `ConversationPanel{localId, hidden}`, W-11 `Composer{localId}`).
 *
 * A plain-text rendering of the conversation so the shell can be exercised and screenshotted
 * with real data: user bubbles, assistant text, one line per tool call, notices. No markdown,
 * no scrolling rules, no cards: all of that is W-09/W-10. The empty states follow the mockups
 * (phone (i): Archie greeting; agent "Start a conversation").
 */
import { useSession, useShallow } from '@/stores';
import { Chip, EmptyState } from '@/ui/controls';
import { ScrollArea } from '@/ui/primitives';
import { ArchieMark } from '../shell/ArchieMark';
import { greeting } from '../shell/greeting';
import { SlotNote } from './SlotNote';
import styles from './placeholders.module.css';

interface Line {
  id: string;
  who: 'user' | 'assistant' | 'tool' | 'notice';
  text: string;
}

export function ConversationPanel({ localId, hidden }: { localId: string; hidden: boolean }) {
  const kind = useSession(localId, (s) => s.conv.ref.kind);
  const lines = useSession(
    localId,
    useShallow((s) => {
      const out: Line[] = [];
      for (const e of s.conv.entries) {
        if (e.kind === 'user') out.push({ id: e.id, who: 'user', text: e.text });
        else if (e.kind === 'notice') out.push({ id: e.id, who: 'notice', text: e.text });
        else
          for (const b of e.blocks) {
            if (b.type === 'text' && b.text) out.push({ id: b.id, who: 'assistant', text: b.text });
            else if (b.type === 'tool') out.push({ id: b.id, who: 'tool', text: b.tool_name });
            else if (b.type === 'permission') out.push({ id: b.id, who: 'tool', text: `${b.tool_name} · ${b.state}` });
          }
      }
      return out;
    }),
  );
  const empty = lines.length === 0;
  return (
    <div className={styles.conversation} data-hidden={hidden ? '' : undefined}>
      <ScrollArea className={styles.scroll}>
        <div className={empty ? `${styles.column} ${styles.columnEmpty}` : styles.column}>
          {empty ? (
            kind === 'orchestrator' ? (
              <EmptyState
                variant="hero"
                media={<ArchieMark size={72} />}
                title={
                  <>
                    {greeting()}
                    <br />
                    What are we doing?
                  </>
                }
                description="Talk or type. Archie can hand work to an agent."
              >
                <Chip variant="suggestion" icon="tv">
                  Plan the living-room TV setup
                </Chip>
                <Chip variant="suggestion" icon="book_2">
                  What do I know about voice?
                </Chip>
              </EmptyState>
            ) : (
              <EmptyState icon="terminal" title="Start a conversation" description="Ask the agent to read, change or run something." />
            )
          ) : (
            lines.map((l) =>
              l.who === 'user' ? (
                <p key={l.id} className={styles.user}>
                  {l.text}
                </p>
              ) : l.who === 'assistant' ? (
                <p key={l.id} className={styles.assistant}>
                  {l.text}
                </p>
              ) : (
                <p key={l.id} className={styles.meta}>
                  {l.who === 'tool' ? `⚙ ${l.text}` : l.text}
                </p>
              ),
            )
          )}
          <SlotNote owner="W-09">Conversation view</SlotNote>
        </div>
      </ScrollArea>
      <Composer localId={localId} />
    </div>
  );
}

/** PLACEHOLDER (W-11 `Composer`). Not a form field: the 16 px field rule belongs to W-11. */
export function Composer({ localId }: { localId: string }) {
  const kind = useSession(localId, (s) => s.conv.ref.kind);
  return (
    <div className={styles.composerWrap}>
      <div className={styles.composer} data-placeholder="W-11">
        <span>{kind === 'orchestrator' ? 'Message Archie…' : 'Message the agent…'}</span>
        <span className={styles.composerNote}>composer · W-11</span>
      </div>
    </div>
  );
}
