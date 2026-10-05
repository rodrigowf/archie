/**
 * Entry point of `@/features/tools` (W-10, spec 13 §3.6, §3.9).
 *
 *   <ToolCard block sessionKind autoOpen? stalled? feed? />   one tool call (R7: output always reachable)
 *   <StepGroup blocks live sessionKind? stalledToolUseId? />  "N steps" group (IA §9.3)
 *
 * Grouping is computed by `groupToolSteps(run.blocks, { live: isRunLive(conv, i), enabled: pref })`
 * from @/protocol; render `kind: 'steps'` items with StepGroup and tool blocks with ToolCard. A
 * solo card should get `autoOpen` = "this block is the live tail of a turn in flight", so it stays
 * open after its result arrives and folds once text follows.
 */
export { ToolCard, StatusGlyph, type ToolCardProps } from './ToolCard';
export { StepGroup, groupStatus, groupSummary, type StepGroupProps } from './StepGroup';
export { OutputView, type OutputViewProps } from './OutputView';
export { getToolSpec, headline, resolveTool, TOOL_SPECS, type ResolvedTool, type ToolCategory, type ToolSpec } from './registry';
export { normalizeTool, normalizeToolInput, normalizeToolName, ORCHESTRATOR_TOOLS, SNAKE_TO_CLAUDE, type NormalizedTool } from './normalize';
export { OUTPUT_MAX_CHARS, OUTPUT_MAX_LINES, stripAnsi, truncateOutput } from './format';
