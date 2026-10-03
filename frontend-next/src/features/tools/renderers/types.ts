import type { ToolBlock } from '@/protocol';
import type { ToolInput } from '../format';
import type { OutputViewProps } from '../OutputView';

/** Props of every per-tool body (the expanded part of a card: input details + output region). */
export interface ToolBodyProps {
  readonly block: ToolBlock;
  /** Normalised input (Claude argument keys). */
  readonly input: ToolInput;
  /** Canonical tool name. */
  readonly name: string;
  /** Pass-through props for the output region (timing, live feed, waiting text). */
  readonly out: Pick<OutputViewProps, 'runningSeconds' | 'feed' | 'feedTitle' | 'runningText'>;
}
