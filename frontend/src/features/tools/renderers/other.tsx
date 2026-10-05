/** Bodies of the web, orchestrator agent-session, search and generic tools. */
import { EXTERNAL_LINK_PROPS } from '@/features/markdown';
import { arr, num, prettyJson, str } from '../format';
import { OutputView } from '../OutputView';
import styles from '../ToolCard.module.css';
import { Fields, PreText, Section } from './parts';
import type { ToolBodyProps } from './types';

function safeHref(url: string): string | null {
  return /^https?:\/\//i.test(url) ? url : null;
}

export function WebFetchBody({ block, input, out }: ToolBodyProps) {
  const url = str(input, 'url') ?? '';
  const href = safeHref(url);
  return (
    <Section>
      <Fields
        rows={[
          [
            'URL',
            href ? (
              <a className={styles.link} href={href} {...EXTERNAL_LINK_PROPS}>
                {url}
              </a>
            ) : (
              url
            ),
            true,
          ],
          ['Prompt', str(input, 'prompt')],
        ]}
      />
      <OutputView block={block} {...out} />
    </Section>
  );
}

export function WebSearchBody({ block, input, out }: ToolBodyProps) {
  const allowed = arr(input, 'allowed_domains');
  const blocked = arr(input, 'blocked_domains');
  return (
    <Section>
      <Fields
        rows={[
          ['Query', str(input, 'query')],
          ['Only', allowed && allowed.length ? allowed.map(String).join(', ') : undefined],
          ['Except', blocked && blocked.length ? blocked.map(String).join(', ') : undefined],
        ]}
      />
      <OutputView block={block} {...out} />
    </Section>
  );
}

/**
 * send_to_agent_session (inv02 F-05 SendToAgentBlock): the target session and the message. While
 * the call runs, a live feed (nested agent activity supplied by the conversation) streams into
 * the output region (mockups phone (a)).
 */
export function SendToAgentBody({ block, input, out }: ToolBodyProps) {
  const message = str(input, 'message');
  return (
    <Section>
      <Fields rows={[['Session', str(input, 'session_id'), true]]} />
      {message ? <PreText>{message}</PreText> : null}
      <OutputView block={block} {...out} />
    </Section>
  );
}

/** close / read / interrupt / open agent session, respond_to_agent_permission, list tools. */
export function AgentSessionBody({ block, input, out }: ToolBodyProps) {
  const max = num(input, 'max_messages');
  return (
    <Section>
      <Fields
        rows={[
          ['Session', str(input, 'session_id'), true],
          ['Resume', str(input, 'resume_sdk_id'), true],
          ['Title', str(input, 'title')],
          ['Directory', str(input, 'working_directory') ?? str(input, 'working_dir'), true],
          ['Request', str(input, 'request_id'), true],
          ['Decision', str(input, 'decision')],
          ['Message', str(input, 'message')],
          ['Max', max !== undefined ? `${max} messages` : undefined],
          ['Limit', str(input, 'limit')],
        ]}
      />
      <OutputView block={block} {...out} />
    </Section>
  );
}

export function SearchBody({ block, input, out }: ToolBodyProps) {
  return (
    <Section>
      <Fields rows={[['Query', str(input, 'query')], ['Max', str(input, 'max_results')]]} />
      <OutputView block={block} {...out} />
    </Section>
  );
}

/** Default (inv02 F-05): the input as pretty JSON, then the output. */
export function GenericBody({ block, input, out }: ToolBodyProps) {
  const empty = Object.keys(input).length === 0;
  return (
    <Section>
      {empty ? null : <pre className={`${styles.pre} ${styles.json}`}>{prettyJson(input)}</pre>}
      <OutputView block={block} {...out} />
    </Section>
  );
}
