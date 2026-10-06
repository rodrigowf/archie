/** W-03 gallery: actions (Button, IconButton, Fab). Dev-only. */
import { useState } from 'react';
import { Button, type ButtonVariant } from '../Button/Button';
import { Fab } from '../Fab/Fab';
import { IconButton, type IconButtonVariant } from '../IconButton/IconButton';
import { Board, Boards, FORCED, Note, Spec, StateRow } from './Board';

const VARIANTS: { v: ButtonVariant; label: string }[] = [
  { v: 'filled', label: 'Filled' },
  { v: 'tonal', label: 'Tonal' },
  { v: 'outlined', label: 'Outlined' },
  { v: 'text', label: 'Text' },
  { v: 'elevated', label: 'Elevated' },
];

const IB_VARIANTS: IconButtonVariant[] = ['standard', 'outlined', 'tonal', 'filled'];

function ButtonsBoard() {
  const [saving, setSaving] = useState(false);
  return (
    <Board title="Buttons" note="40 dp, full radius">
      <Spec>
        <Button variant="filled">Approve</Button>
        <Button variant="tonal">Save</Button>
        <Button variant="outlined">Reject</Button>
        <Button variant="text">Details</Button>
        <Button variant="elevated">Retry</Button>
      </Spec>
      <Spec>
        <Button icon="send">Send</Button>
        <Button variant="tonal" icon="cast">
          Show on TV
        </Button>
        <Button variant="outlined" icon="terminal">
          New agent
        </Button>
        <Button disabled>Disabled</Button>
      </Spec>
      <Spec>
        <Button size="large" icon="graphic_eq">
          Talk to Archie
        </Button>
      </Spec>
      <Spec>
        <Button variant="filled" tone="error" icon="refresh" size="small">
          Reconnect
        </Button>
        <Button variant="filled" tone="warning">
          Interrupt
        </Button>
        <Button variant="filled" tone="error">
          Retry
        </Button>
        <Button variant="text" tone="error">
          Delete
        </Button>
        <Button
          variant="tonal"
          loading={saving}
          icon="check"
          onClick={() => {
            setSaving(true);
            window.setTimeout(() => {
              setSaving(false);
            }, 1500);
          }}
        >
          {saving ? 'Saving' : 'Save (try me)'}
        </Button>
      </Spec>
    </Board>
  );
}

function ButtonStatesBoard() {
  return (
    <Board title="Button states" note="enabled · hover · focus · pressed · disabled · loading">
      <StateRow label="enabled">
        {VARIANTS.map(({ v, label }) => (
          <Button key={v} variant={v}>
            {label}
          </Button>
        ))}
      </StateRow>
      {FORCED.map((st) => (
        <StateRow key={st} label={st}>
          {VARIANTS.map(({ v, label }) => (
            <Button key={v} variant={v} data-state={st} tabIndex={-1}>
              {label}
            </Button>
          ))}
        </StateRow>
      ))}
      <StateRow label="disabled">
        {VARIANTS.map(({ v, label }) => (
          <Button key={v} variant={v} disabled>
            {label}
          </Button>
        ))}
      </StateRow>
      <StateRow label="loading">
        {VARIANTS.map(({ v, label }) => (
          <Button key={v} variant={v} loading>
            {label}
          </Button>
        ))}
      </StateRow>
    </Board>
  );
}

function IconButtonsBoard() {
  const [muted, setMuted] = useState(false);
  const [speaker, setSpeaker] = useState(true);
  return (
    <Board title="Icon buttons and FABs" note="48 dp targets">
      <Spec>
        <IconButton icon="more_vert" aria-label="Standard" />
        <IconButton icon="mic" variant="outlined" aria-label="Outlined" />
        <IconButton icon="volume_up" variant="tonal" aria-label="Tonal" />
        <IconButton icon="arrow_upward" variant="filled" aria-label="Filled" />
        <IconButton icon="mic_off" selected aria-label="Selected" />
        <IconButton icon="call_end" tone="error" filled aria-label="End" />
        <IconButton icon="arrow_upward" variant="filled" disabled aria-label="Disabled" />
      </Spec>
      <Spec space="4">
        <Fab size="small" icon="add" aria-label="Small FAB" />
        <Fab icon="add" aria-label="FAB" />
        <Fab size="large" icon="graphic_eq" aria-label="Large FAB" />
        <Fab extended icon="add">
          New
        </Fab>
      </Spec>
      <Spec>
        <IconButton
          icon="mic"
          selectedIcon="mic_off"
          variant="tonal"
          selected={muted}
          onClick={() => {
            setMuted(!muted);
          }}
          aria-label="Mute microphone"
        />
        <IconButton
          icon="volume_off"
          selectedIcon="volume_off"
          variant="tonal"
          selected={!speaker}
          onClick={() => {
            setSpeaker(!speaker);
          }}
          aria-label="Mute speaker"
        />
        <IconButton icon="more_vert" size="small" aria-label="Session menu" />
        <IconButton icon="close" size="small" iconSize={20} aria-label="Dismiss" />
        <IconButton icon="refresh" variant="tonal" loading aria-label="Refreshing" />
      </Spec>
      <Note>Toggles (aria-pressed): tap the mic and speaker. Small: 40 dp visual in a 48 dp target.</Note>
    </Board>
  );
}

function IconStatesBoard() {
  return (
    <Board title="Icon button and FAB states" note="hover · focus · pressed · disabled">
      {FORCED.map((st) => (
        <StateRow key={st} label={st}>
          {IB_VARIANTS.map((v) => (
            <IconButton key={v} icon="mic" variant={v} data-state={st} tabIndex={-1} aria-label={`${v} ${st}`} />
          ))}
          <IconButton icon="mic_off" selected data-state={st} tabIndex={-1} aria-label={`selected ${st}`} />
          <Fab size="small" icon="add" data-state={st} tabIndex={-1} aria-label={`FAB ${st}`} />
        </StateRow>
      ))}
      <StateRow label="disabled">
        {IB_VARIANTS.map((v) => (
          <IconButton key={v} icon="mic" variant={v} disabled aria-label={`${v} disabled`} />
        ))}
        <IconButton icon="mic_off" selected disabled aria-label="selected disabled" />
        <Fab size="small" icon="add" disabled aria-label="FAB disabled" />
      </StateRow>
    </Board>
  );
}

export function ActionsGallery() {
  return (
    <Boards>
      <ButtonsBoard />
      <IconButtonsBoard />
      <ButtonStatesBoard />
      <IconStatesBoard />
    </Boards>
  );
}
