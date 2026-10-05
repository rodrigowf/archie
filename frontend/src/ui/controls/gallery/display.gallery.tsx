/** W-03 gallery: display components. Dev-only. */
import { useState } from 'react';
import { Icon } from '@/ui/primitives';
import { Badge } from '../Badge/Badge';
import { Button } from '../Button/Button';
import { Card } from '../Card/Card';
import { Chip } from '../Chip/Chip';
import { Tag } from '../Chip/Tag';
import { CircularProgress } from '../CircularProgress/CircularProgress';
import { Disclosure } from '../Disclosure/Disclosure';
import { Divider } from '../Divider/Divider';
import { EmptyState } from '../EmptyState/EmptyState';
import { IconButton } from '../IconButton/IconButton';
import { LinearProgress } from '../LinearProgress/LinearProgress';
import { List, ListItem } from '../List/List';
import { StatusDot } from '../StatusDot/StatusDot';
import { Switch } from '../Switch/Switch';
import { Board, Boards, FORCED, Note, Spec, StateRow } from './Board';
import s from './gallery.module.css';

function ChipsBoard() {
  const [archie, setArchie] = useState(true);
  const [agents, setAgents] = useState(false);
  const [files, setFiles] = useState(['refactor.md']);
  return (
    <Board title="Chips" note="assist · filter · input · provider">
      <Spec>
        <Chip icon="tv">Plan the TV setup</Chip>
        <Chip variant="filter" selected={archie} onSelectedChange={setArchie}>
          Archie
        </Chip>
        <Chip variant="filter" selected={agents} onSelectedChange={setAgents}>
          Agents
        </Chip>
        {files.map((f) => (
          <Chip
            key={f}
            variant="input"
            onRemove={() => {
              setFiles(files.filter((x) => x !== f));
            }}
          >
            {f}
          </Chip>
        ))}
      </Spec>
      <Spec space="3">
        <Tag>Claude</Tag>
        <Tag>Qwen</Tag>
        <Tag>Gemini</Tag>
        <Tag variant="scope" icon="dns">
          Archie (server)
        </Tag>
        <Tag variant="scope" icon="mobile">
          This device
        </Tag>
      </Spec>
      <Spec space="4">
        <StatusDot status="idle" showLabel />
        <StatusDot status="working" showLabel />
        <StatusDot status="warning" showLabel />
        <StatusDot status="disconnected" showLabel />
        <StatusDot status="off" showLabel />
      </Spec>
    </Board>
  );
}

function ChipStatesBoard() {
  return (
    <Board title="Chip states" note="hover · focus · pressed · disabled · suggestion">
      {FORCED.map((st) => (
        <StateRow key={st} label={st}>
          <Chip icon="tv" data-state={st} tabIndex={-1}>
            Assist
          </Chip>
          <Chip variant="filter" selected data-state={st} tabIndex={-1}>
            Filter
          </Chip>
          <Chip variant="filter" data-state={st} tabIndex={-1}>
            Filter
          </Chip>
        </StateRow>
      ))}
      <StateRow label="disabled">
        <Chip icon="tv" disabled>
          Assist
        </Chip>
        <Chip variant="filter" selected disabled>
          Filter
        </Chip>
        <Chip variant="input" disabled>
          notes.md
        </Chip>
      </StateRow>
      <Spec space="2">
        <Chip variant="suggestion" icon="tv">
          Plan the living-room TV setup
        </Chip>
        <Chip variant="suggestion" icon="bolt">
          This week&apos;s energy use
        </Chip>
        <Chip variant="suggestion" icon="book_2">
          What do I know about voice?
        </Chip>
      </Spec>
      <Spec>
        <Chip icon="data_object" trailingIcon="keyboard_arrow_down" aria-expanded={false}>
          Frontmatter · architecture · 4 refs
        </Chip>
      </Spec>
    </Board>
  );
}

function BadgesProgressBoard() {
  return (
    <Board title="Badges and progress" note="dot · count · linear · circular · context ring">
      <Spec space="6">
        <Badge count={3} label="3 sessions need you">
          <Icon name="forum" />
        </Badge>
        <Badge dot label="New activity">
          <Icon name="book_2" />
        </Badge>
        <Badge count={128} label="128 unread">
          <Icon name="history" />
        </Badge>
        <Badge count={7} label="7 new" />
      </Spec>
      <LinearProgress aria-label="Upload" value={0.42} />
      <LinearProgress aria-label="Todo progress" value={3 / 7} valueText="3 of 7" />
      <LinearProgress aria-label="Loading history" />
      <Spec space="4">
        <CircularProgress aria-label="Context used" value={0.32} size={20} thickness={3} />
        <CircularProgress aria-label="Context used (warning)" value={0.62} size={20} thickness={3} tone="warning" />
        <CircularProgress aria-label="Context used (error)" value={0.86} size={20} thickness={3} tone="error" />
        <CircularProgress aria-label="Download" value={0.7} />
        <CircularProgress aria-label="Connecting" />
        <CircularProgress aria-label="Connecting (small)" size={24} thickness={3} />
      </Spec>
    </Board>
  );
}

function CardsBoard() {
  return (
    <Board title="Cards and dividers" note="filled · elevated · outlined · actionable">
      <div className={s.cards}>
        {(['filled', 'elevated', 'outlined'] as const).map((v) => (
          <Card key={v} variant={v}>
            <div className={s.cardBody}>
              <div className={s.cardTitle}>{v[0]?.toUpperCase() + v.slice(1)}</div>
              <span className={s.cardText}>Static container</span>
            </div>
          </Card>
        ))}
        <Card variant="filled" onClick={() => undefined}>
          <span className={s.cardBody}>
            <span className={s.cardTitle}>Energy dashboard</span>
            <span className={s.cardText}>projects/energy · 2 h ago</span>
          </span>
        </Card>
        <Card variant="elevated" onClick={() => undefined} data-state="hover">
          <span className={s.cardBody}>
            <span className={s.cardTitle}>Hover</span>
            <span className={s.cardText}>actionable, elevated</span>
          </span>
        </Card>
        <Card variant="outlined" onClick={() => undefined} disabled>
          <span className={s.cardBody}>
            <span className={s.cardTitle}>Disabled</span>
            <span className={s.cardText}>aria-disabled</span>
          </span>
        </Card>
      </div>
      <Divider />
      <Divider inset="middle" />
      <div className={s.rowDivider}>
        <span>Left</span>
        <Divider orientation="vertical" inset="middle" style={{ margin: '8px 16px' }} />
        <span>Right</span>
      </div>
    </Board>
  );
}

function ListsBoard() {
  const [current, setCurrent] = useState('refactor');
  const [wake, setWake] = useState(true);
  return (
    <Board title="Lists" note="one · two · three line; selectable; trailing meta and actions">
      <List aria-label="Open now">
        <ListItem
          leading="star_shine"
          headline="Living-room TV"
          supporting="Archie · waiting for approval"
          trailing={<StatusDot status="warning" />}
          selected={current === 'tv'}
          current="page"
          onClick={() => {
            setCurrent('tv');
          }}
        />
        <ListItem
          leading="terminal"
          headline="Refactor voice module"
          supporting={
            <>
              <Tag>Claude</Tag>
              <span>Ready</span>
            </>
          }
          trailing={<StatusDot status="idle" />}
          selected={current === 'refactor'}
          current="page"
          onClick={() => {
            setCurrent('refactor');
          }}
        />
        <ListItem
          leading="terminal"
          headline="Energy dashboard"
          supporting={
            <>
              <Tag>Qwen</Tag>
              <span>Using Bash</span>
            </>
          }
          trailing={<StatusDot status="working" />}
          selected={current === 'energy'}
          current="page"
          onClick={() => {
            setCurrent('energy');
          }}
          action={<IconButton icon="more_vert" size="small" aria-label="Energy dashboard menu" />}
        />
        <ListItem leading="star_shine" headline="Weekly energy report" meta="14:20" onClick={() => undefined} />
        <ListItem leading="terminal" headline="Hover" meta="11:05" onClick={() => undefined} data-state="hover" />
        <ListItem leading="terminal" headline="Focus" meta="Thu" onClick={() => undefined} data-state="focus" />
        <ListItem leading="terminal" headline="Pressed" meta="Mon" onClick={() => undefined} data-state="pressed" />
        <ListItem leading="terminal" headline="Disabled" meta="Sun" onClick={() => undefined} disabled />
      </List>
      <Divider />
      <List aria-label="Settings">
        <ListItem
          size="large"
          leading="palette"
          headline="Appearance"
          supporting="Theme, text size, motion"
          trailing={<Icon name="chevron_right" />}
          onClick={() => undefined}
        />
        <ListItem
          size="large"
          leading="hearing"
          headline="Wake word"
          supporting="Vosk + Whisper confirmation"
          action={<Switch aria-label="Wake word" checked={wake} onCheckedChange={setWake} />}
        />
        <ListItem
          leading="description"
          overline="assistant/architecture"
          headline="voice_subsystem.md"
          supporting="Voice lifecycle, providers and the restart race fix; links to wakeword_subsystem.md and the refactor methodology."
          lines={3}
          href="#/dev/gallery/display"
        />
      </List>
    </Board>
  );
}

function DisclosureEmptyBoard() {
  return (
    <Board title="Disclosure and empty states" note="aria-expanded + aria-controls; body outside the button">
      <Disclosure summary="Thought for 4 s" icon="star_shine" meta="312 words">
        <Note>The plan is to replace the voice module&apos;s boolean with the state machine.</Note>
      </Disclosure>
      <Disclosure summary="Open (default)" icon="info" defaultOpen>
        <Note>The body is a sibling of the header button.</Note>
      </Disclosure>
      <Disclosure summary="Hover" icon="info" data-state="hover">
        <Note>hidden</Note>
      </Disclosure>
      <Disclosure variant="chip" icon="data_object" summary="Frontmatter · architecture · 4 refs">
        <pre className={s.mono}>{'category: architecture\nreferences: 4'}</pre>
      </Disclosure>
      <div className={s.emptyFrame}>
        <EmptyState icon="forum" title="No conversations yet" description="Start one, or talk to Archie.">
          <Button icon="add" variant="tonal">
            New session
          </Button>
        </EmptyState>
      </div>
      <div className={s.emptyFrame}>
        <EmptyState
          variant="hero"
          icon="graphic_eq"
          title={
            <>
              Good evening.
              <br />
              What are we doing?
            </>
          }
        >
          <Chip variant="suggestion" icon="tv">
            Plan the living-room TV setup
          </Chip>
          <Chip variant="suggestion" icon="terminal">
            Start an agent session
          </Chip>
        </EmptyState>
      </div>
    </Board>
  );
}

export function DisplayGallery() {
  return (
    <Boards>
      <ChipsBoard />
      <ChipStatesBoard />
      <BadgesProgressBoard />
      <CardsBoard />
      <ListsBoard />
      <DisclosureEmptyBoard />
    </Boards>
  );
}
