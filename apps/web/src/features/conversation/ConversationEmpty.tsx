/**
 * Empty states (IA §6, mockups phone (i); inv02 F-40): a new Archie conversation shows a greeting,
 * one large voice button and suggestion chips, never a blank screen (fixes A6); an agent session
 * shows "Start a conversation".
 */
import type { SessionKind } from '@/protocol';
import { Chip, EmptyState, Fab } from '@/ui/controls';
import { ArchieMark, greeting } from './ArchieMark';
import styles from './Conversation.module.css';

/** Prompts offered on a new Archie conversation (mockups (i)); tapping one fills the composer. */
export const ARCHIE_SUGGESTIONS: readonly { icon: 'tv' | 'bolt' | 'book_2'; text: string }[] = [
  { icon: 'tv', text: 'Plan the living-room TV setup' },
  { icon: 'bolt', text: "This week's energy use" },
  { icon: 'book_2', text: 'What do I know about voice?' },
];

export interface ConversationEmptyProps {
  readonly kind: SessionKind;
  readonly onStartVoice?: () => void;
  readonly onSuggestion?: (text: string) => void;
}

export function ConversationEmpty({ kind, onStartVoice, onSuggestion }: ConversationEmptyProps) {
  if (kind === 'agent') {
    return (
      <div className={styles.empty}>
        <EmptyState
          icon="terminal"
          title="Start a conversation"
          description="Send a message to begin. The agent can read, change and run things in its working directory."
        />
      </div>
    );
  }
  return (
    <div className={styles.empty}>
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
        {onStartVoice ? (
          <div className={styles.bigVoice}>
            <Fab icon="graphic_eq" color="primary" size="large" aria-label="Start voice conversation" onClick={onStartVoice} />
            <span className={styles.bigVoiceLabel}>Tap to talk</span>
          </div>
        ) : null}
      </EmptyState>
      {onSuggestion ? (
        <div className={styles.suggestions}>
          {ARCHIE_SUGGESTIONS.map((s) => (
            <Chip key={s.text} variant="suggestion" icon={s.icon} className={styles.suggestion} onClick={() => onSuggestion(s.text)}>
              {s.text}
            </Chip>
          ))}
        </div>
      ) : null}
    </div>
  );
}
