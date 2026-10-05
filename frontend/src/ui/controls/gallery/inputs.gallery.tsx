/** W-03 gallery: inputs and selection. Dev-only. */
import { useState } from 'react';
import { Checkbox } from '../Checkbox/Checkbox';
import { IconButton } from '../IconButton/IconButton';
import { Radio, RadioGroup } from '../Radio/Radio';
import { SearchField } from '../SearchField/SearchField';
import { SegmentedButton } from '../SegmentedButton/SegmentedButton';
import { Select, type SelectOption } from '../Select/Select';
import { Slider } from '../Slider/Slider';
import { Switch } from '../Switch/Switch';
import { TextField } from '../TextField/TextField';
import { Board, Boards, FORCED, Note, Spec, StateRow } from './Board';
import s from './gallery.module.css';

const MODELS: SelectOption[] = [
  { value: 'gpt-realtime', label: 'gpt-realtime', description: 'OpenAI · WebRTC' },
  { value: 'gpt-realtime-mini', label: 'gpt-realtime-mini', description: 'OpenAI · cheaper' },
  { value: 'gemini-live', label: 'gemini-2.5-flash-live', description: 'Google' },
  { value: 'qwen-omni', label: 'qwen-omni-turbo', description: 'DashScope', disabled: true },
];

function TextFieldsBoard() {
  return (
    <Board title="Text fields" note="outlined · filled · error">
      <TextField label="Server address" defaultValue="archie.local" supportingText="Port 8765 is added for you" data-state="focus" />
      <TextField variant="filled" label="Wake phrase" defaultValue="Hey Archie" />
      <TextField label="SSH host" defaultValue="jetson.local:22a" error="Port must be a number, e.g. jetson.local:22" />
    </Board>
  );
}

function TextFieldStatesBoard() {
  const [secret, setSecret] = useState(true);
  return (
    <Board title="Text field states" note="empty · hover · icons · multiline · disabled">
      <TextField label="Working directory" placeholder="/home/rodrigo/projects" />
      <TextField label="Hover" data-state="hover" />
      <TextField
        label="API key"
        type={secret ? 'password' : 'text'}
        defaultValue="sk-archie-123456"
        leadingIcon="shield"
        trailing={
          <IconButton
            icon={secret ? 'visibility' : 'visibility_off'}
            aria-label={secret ? 'Show key' : 'Hide key'}
            onClick={() => {
              setSecret(!secret);
            }}
          />
        }
      />
      <TextField variant="filled" label="Search memory" leadingIcon="search" trailing="mic" />
      <TextField variant="filled" label="Port" defaultValue="87a5" error="Must be a number" />
      <TextField variant="filled" label="Hover (filled)" data-state="hover" />
      <TextField
        label="System prompt"
        multiline
        maxRows={5}
        defaultValue={'You are Archie.\nKeep answers short.'}
        supportingText="Grows up to 5 lines"
      />
      <TextField label="Disabled" defaultValue="read-only value" disabled />
      <TextField variant="filled" label="Disabled (filled)" disabled />
    </Board>
  );
}

function SearchSelectBoard() {
  const [q, setQ] = useState('voice');
  const [model, setModel] = useState('gpt-realtime');
  return (
    <Board title="Search and select" note="search bar · menu select · native select">
      <SearchField label="Search conversations" />
      <SearchField label="Search memory files" value={q} onValueChange={setQ} />
      <SearchField label="Focused" data-state="focus" />
      <Select
        label="Voice model"
        options={MODELS}
        value={model}
        onChange={setModel}
        supportingText="Menu on pointer devices"
        native={false}
      />
      <Select
        label="Voice model (native)"
        options={MODELS}
        defaultValue="gemini-live"
        native
        supportingText="Native picker on touch and compat"
      />
      <Select variant="filled" label="Placeholder" options={MODELS} placeholder="Pick a model" native={false} />
      <Select label="Error" options={MODELS} error="Model not available" native={false} />
      <Select label="Disabled" options={MODELS} defaultValue="gpt-realtime" disabled native={false} />
      <div className={s.selectPad}>
        <Select label="Open (forced)" options={MODELS} defaultValue="gpt-realtime-mini" native={false} data-state="open" />
      </div>
    </Board>
  );
}

function SelectionBoard() {
  const [on, setOn] = useState(true);
  const [vad, setVad] = useState(0.3);
  const [live, setLive] = useState(0.3);
  const [commits, setCommits] = useState(0);
  const [theme, setTheme] = useState<'system' | 'dark' | 'light'>('dark');
  return (
    <Board title="Selection" note="switch · slider · segmented">
      <Spec>
        <Switch aria-label="On" checked={on} onCheckedChange={setOn} />
        <Switch aria-label="Off" defaultChecked={false} />
        <Switch aria-label="Disabled" checked disabled />
      </Spec>
      <div className={s.sliderPad}>
        <Slider
          aria-label="VAD threshold (preview)"
          min={0}
          max={0.7}
          step={0.01}
          value={0.3}
          valueLabel="always"
          formatValue={(x) => x.toFixed(2)}
        />
      </div>
      <SegmentedButton
        aria-label="Theme"
        fullWidth
        value={theme}
        onChange={setTheme}
        options={[
          { value: 'system', label: 'System', icon: 'brightness_auto' },
          { value: 'dark', label: 'Dark', icon: 'dark_mode' },
          { value: 'light', label: 'Light', icon: 'light_mode' },
        ]}
      />
      <div className={s.sliderPad}>
        <Slider
          aria-label="VAD threshold"
          min={0}
          max={1}
          step={0.01}
          value={vad}
          onValueChange={setLive}
          onCommit={(v) => {
            setVad(v);
            setCommits((n) => n + 1);
          }}
          formatValue={(x) => x.toFixed(2)}
        />
      </div>
      <Note>
        Drag: live {live.toFixed(2)} · committed {vad.toFixed(2)} · {commits} commit{commits === 1 ? '' : 's'} (one per release, never per
        tick)
      </Note>
    </Board>
  );
}

function SelectionStatesBoard() {
  return (
    <Board title="Selection states" note="hover · focus · pressed · disabled">
      {FORCED.map((st) => (
        <StateRow key={st} label={st}>
          <Switch aria-label={`Off ${st}`} data-state={st} tabIndex={-1} />
          <Switch aria-label={`On ${st}`} defaultChecked data-state={st} tabIndex={-1} />
          <Switch aria-label={`Icon ${st}`} defaultChecked showIcon data-state={st} tabIndex={-1} />
        </StateRow>
      ))}
      <StateRow label="disabled">
        <Switch aria-label="Off disabled" disabled />
        <Switch aria-label="On disabled" defaultChecked disabled />
      </StateRow>
      <div className={s.sliderPad}>
        <Slider aria-label="Speaker level (pressed)" defaultValue={80} data-state="pressed" formatValue={(x) => `${String(x)}%`} />
      </div>
      <Slider aria-label="Speaker level (focus)" defaultValue={35} data-state="focus" valueLabel="never" />
      <Slider aria-label="Microphone level (disabled)" defaultValue={60} disabled />
      <div>
        <SegmentedButton
          aria-label="Wake word engines"
          multiple
          defaultValue={['vosk'] as string[]}
          options={[
            { value: 'vosk', label: 'Vosk' },
            { value: 'whisper', label: 'Whisper' },
            { value: 'sr', label: 'System', disabled: true },
          ]}
        />
      </div>
      <div>
        <SegmentedButton
          aria-label="Density"
          disabled
          defaultValue="cozy"
          options={[
            { value: 'cozy', label: 'Cozy' },
            { value: 'compact', label: 'Compact' },
          ]}
        />
      </div>
      <div>
        <Switch label="Reduce motion" defaultChecked />
      </div>
    </Board>
  );
}

function ChecksBoard() {
  const [tz, setTz] = useState('auto');
  return (
    <Board title="Checkboxes and radios" note="unchecked · checked · mixed · error · disabled">
      <StateRow label="enabled">
        <Checkbox aria-label="Unchecked" />
        <Checkbox aria-label="Checked" defaultChecked />
        <Checkbox aria-label="Mixed" indeterminate />
        <Radio aria-label="Radio off" value="a" name="g-demo" />
        <Radio aria-label="Radio on" value="b" name="g-demo" defaultChecked />
      </StateRow>
      {FORCED.map((st) => (
        <StateRow key={st} label={st}>
          <Checkbox aria-label={`Unchecked ${st}`} data-state={st} tabIndex={-1} />
          <Checkbox aria-label={`Checked ${st}`} defaultChecked data-state={st} tabIndex={-1} />
          <Radio aria-label={`Radio ${st}`} value="x" name={`g-${st}`} data-state={st} tabIndex={-1} />
          <Radio aria-label={`Radio on ${st}`} value="y" name={`g-${st}`} defaultChecked data-state={st} tabIndex={-1} />
        </StateRow>
      ))}
      <StateRow label="error">
        <Checkbox aria-label="Error" error />
        <Checkbox aria-label="Error checked" error defaultChecked />
      </StateRow>
      <StateRow label="disabled">
        <Checkbox aria-label="Disabled" disabled />
        <Checkbox aria-label="Disabled checked" disabled defaultChecked />
        <Checkbox aria-label="Disabled mixed" disabled indeterminate />
        <Radio aria-label="Radio disabled" value="c" name="g-dis" disabled />
        <Radio aria-label="Radio disabled on" value="d" name="g-dis" disabled defaultChecked />
      </StateRow>
      <div>
        <Checkbox label="Group tool steps" defaultChecked />
      </div>
      <RadioGroup label="Time zone" value={tz} onChange={setTz}>
        <Radio value="auto" label="Automatic (America/Sao_Paulo)" />
        <Radio value="utc" label="UTC" />
        <Radio value="jetson" label="Same as the server" />
      </RadioGroup>
    </Board>
  );
}

export function InputsGallery() {
  return (
    <Boards>
      <TextFieldsBoard />
      <SelectionBoard />
      <TextFieldStatesBoard />
      <SearchSelectBoard />
      <SelectionStatesBoard />
      <ChecksBoard />
    </Boards>
  );
}
