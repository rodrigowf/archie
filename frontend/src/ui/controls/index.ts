/**
 * Entry point of `@/ui/controls` (W-03, spec 13 §3.5, §3.9): actions, inputs, selection and
 * display components. Built on `@/ui/primitives` and the tokens; no flex gap anywhere (Safari 12).
 * Only TextField, SearchField and Select render text-entry fields (16 px rule, §2.6).
 */
export { Button } from './Button/Button';
export type { ButtonProps, ButtonSize, ButtonTone, ButtonVariant } from './Button/Button';
export { IconButton } from './IconButton/IconButton';
export type { IconButtonProps, IconButtonVariant } from './IconButton/IconButton';
export { Fab } from './Fab/Fab';
export type { FabProps } from './Fab/Fab';

export { TextField } from './TextField/TextField';
export type { TextFieldProps } from './TextField/TextField';
export { SearchField } from './SearchField/SearchField';
export type { SearchFieldProps } from './SearchField/SearchField';
export { Select, prefersNativeSelect } from './Select/Select';
export type { SelectOption, SelectProps } from './Select/Select';
export type { FieldVariant } from './shared/FieldShell';
export { Checkbox } from './Checkbox/Checkbox';
export type { CheckboxProps } from './Checkbox/Checkbox';
export { Radio, RadioGroup } from './Radio/Radio';
export type { RadioGroupProps, RadioProps } from './Radio/Radio';
export { Switch } from './Switch/Switch';
export type { SwitchProps } from './Switch/Switch';
export { Slider } from './Slider/Slider';
export type { SliderProps } from './Slider/Slider';
export { SegmentedButton } from './SegmentedButton/SegmentedButton';
export type {
  MultiSegmentedButtonProps,
  SegmentOption,
  SegmentedButtonProps,
  SingleSegmentedButtonProps,
} from './SegmentedButton/SegmentedButton';

export { Chip } from './Chip/Chip';
export type { ChipProps, ChipVariant } from './Chip/Chip';
export { Tag } from './Chip/Tag';
export type { TagProps } from './Chip/Tag';
export { Badge } from './Badge/Badge';
export type { BadgeProps } from './Badge/Badge';
export { LinearProgress } from './LinearProgress/LinearProgress';
export type { LinearProgressProps } from './LinearProgress/LinearProgress';
export { CircularProgress } from './CircularProgress/CircularProgress';
export type { CircularProgressProps } from './CircularProgress/CircularProgress';
export { Card } from './Card/Card';
export type { CardProps, CardVariant } from './Card/Card';
export { Divider } from './Divider/Divider';
export type { DividerProps } from './Divider/Divider';
export { List, ListItem } from './List/List';
export type { ListItemProps, ListProps } from './List/List';
export { Disclosure } from './Disclosure/Disclosure';
export type { DisclosureProps } from './Disclosure/Disclosure';
export { EmptyState } from './EmptyState/EmptyState';
export type { EmptyStateProps } from './EmptyState/EmptyState';
export { StatusDot, STATUS_LABELS } from './StatusDot/StatusDot';
export type { SessionStatus, StatusDotProps } from './StatusDot/StatusDot';
