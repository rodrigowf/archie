/**
 * The workspace with no open tab (mockups phone (i) "never a blank screen", IA §6): the Archie
 * mark, a greeting and the two "new" actions.
 */
import { Button, EmptyState } from '@/ui/controls';
import { ArchieMark } from '../shell/ArchieMark';
import { newAgent, newArchie } from '../shell/actions';
import { greeting } from '../shell/greeting';
import styles from './workspace.module.css';

export function WorkspaceEmpty() {
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
        <Button variant="filled" icon="add_comment" onClick={newArchie}>
          New Archie conversation
        </Button>
        <Button variant="outlined" icon="terminal" onClick={newAgent}>
          New agent session
        </Button>
      </EmptyState>
    </div>
  );
}
