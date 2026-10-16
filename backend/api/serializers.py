"""Serialize manager Event objects to JSON-compatible dicts."""

from __future__ import annotations

from typing import Any

from manager.types import (
    CompactComplete,
    Event,
    PermissionRequest,
    PermissionResolved,
    SessionStalled,
    SessionTerminated,
    SubagentEvent,
    SubagentFinished,
    SubagentProgress,
    SubagentStarted,
    TextComplete,
    TextDelta,
    ThinkingComplete,
    ThinkingDelta,
    ToolResult,
    ToolUse,
    TurnComplete,
    UnpromptedTurnStarted,
)


def serialize_event(event: Event) -> dict[str, Any]:
    """Convert a manager Event to a dict suitable for JSON WebSocket frames."""
    if isinstance(event, TextDelta):
        return {"type": "text_delta", "text": event.text}
    if isinstance(event, TextComplete):
        return {"type": "text_complete", "text": event.text}
    if isinstance(event, ThinkingDelta):
        return {"type": "thinking_delta", "text": event.text}
    if isinstance(event, ThinkingComplete):
        return {"type": "thinking_complete", "text": event.text}
    if isinstance(event, ToolUse):
        return {
            "type": "tool_use",
            "tool_use_id": event.tool_use_id,
            "tool_name": event.tool_name,
            "tool_input": event.tool_input,
        }
    if isinstance(event, ToolResult):
        return {
            "type": "tool_result",
            "tool_use_id": event.tool_use_id,
            "output": event.output,
            "is_error": event.is_error,
            **({"images": list(event.images)} if event.images else {}),
        }
    if isinstance(event, TurnComplete):
        usage = event.usage or {}
        # Total context size = new tokens + cached tokens read
        total_input = (
            usage.get("input_tokens", 0)
            + usage.get("cache_read_input_tokens", 0)
            + usage.get("cache_creation_input_tokens", 0)
        )
        return {
            "type": "turn_complete",
            "cost": event.cost,
            "usage": usage,
            "input_tokens": total_input,
            "output_tokens": usage.get("output_tokens", 0),
            "num_turns": event.num_turns,
            "session_id": event.session_id,
            "is_error": event.is_error,
            "result": event.result,
        }
    if isinstance(event, CompactComplete):
        return {"type": "compact_complete", "trigger": event.trigger, "summary": event.summary}
    if isinstance(event, SessionStalled):
        return {
            "type": "session_stalled",
            "elapsed_seconds": event.elapsed_seconds,
            "last_tool_name": event.last_tool_name,
            "last_tool_use_id": event.last_tool_use_id,
        }
    if isinstance(event, PermissionRequest):
        return {
            "type": "permission_request",
            "request_id": event.request_id,
            "tool_name": event.tool_name,
            "tool_input": event.tool_input,
        }
    if isinstance(event, PermissionResolved):
        return {
            "type": "permission_resolved",
            "request_id": event.request_id,
            "decision": event.decision,
            "responder": event.responder,
            "message": event.message,
        }
    if isinstance(event, SessionTerminated):
        return {
            "type": "session_terminated",
            "reason": event.reason.value,
            "detail": event.detail,
            "sdk_session_id": event.sdk_session_id,
        }
    # Subagents (spec 12 §4.8).  The wrapper is what gets seq/stream_id
    # (pool._wrap_payload); the inner frame never carries them.
    if isinstance(event, SubagentEvent):
        return {
            "type": "subagent_event",
            "tool_use_id": event.tool_use_id,
            "frame": serialize_event(event.event),
        }
    if isinstance(event, SubagentStarted):
        return {
            "type": "subagent_started",
            "tool_use_id": event.tool_use_id,
            "parent_tool_use_id": event.parent_tool_use_id,
            "agent_type": event.agent_type,
            "description": event.description,
            "background": event.background,
            "started_at": event.started_at,
        }
    if isinstance(event, SubagentProgress):
        return {
            "type": "subagent_progress",
            "tool_use_id": event.tool_use_id,
            "activity": event.activity,
            "usage": _subagent_usage(event.usage),
        }
    if isinstance(event, SubagentFinished):
        return {
            "type": "subagent_finished",
            "tool_use_id": event.tool_use_id,
            "status": event.status,
            "summary": event.summary,
            "usage": _subagent_usage(event.usage),
            "ended_at": event.ended_at,
        }
    if isinstance(event, UnpromptedTurnStarted):
        # SA-7: the CLI's own turn, announced like an echoed prompt.
        return {"type": "user_message", "text": event.text, "source": event.source}
    return {"type": "unknown"}


def _subagent_usage(usage: dict[str, Any] | None) -> dict[str, Any]:
    u = usage or {}
    return {
        "tokens": u.get("tokens"),
        "tool_uses": u.get("tool_uses"),
        "duration_ms": u.get("duration_ms"),
    }


def serialize_orchestrator_event(event: object) -> dict[str, Any]:
    """Convert an orchestrator OrchestratorEvent to a JSON-compatible dict."""
    from orchestrator.types import (
        TextDelta as OTextDelta,
        TextComplete as OTextComplete,
        ToolUseStart,
        ToolResultEvent,
        ToolExecutingEvent,
        ToolProgressEvent,
        NestedSessionEvent,
        TurnComplete as OTurnComplete,
        ErrorEvent,
    )

    if isinstance(event, OTextDelta):
        return {"type": "text_delta", "text": event.text}
    if isinstance(event, OTextComplete):
        return {"type": "text_complete", "text": event.text}
    if isinstance(event, ToolUseStart):
        return {
            "type": "tool_use",
            "tool_use_id": event.tool_call_id,
            "tool_name": event.tool_name,
            "tool_input": event.tool_input,
        }
    if isinstance(event, ToolExecutingEvent):
        return {
            "type": "tool_executing",
            "tool_use_id": event.tool_call_id,
            "tool_name": event.tool_name,
        }
    if isinstance(event, ToolProgressEvent):
        return {
            "type": "tool_progress",
            "tool_use_id": event.tool_call_id,
            "tool_name": event.tool_name,
            "elapsed_seconds": event.elapsed_seconds,
            "message": event.message,
        }
    if isinstance(event, NestedSessionEvent):
        return {
            "type": "nested_session_event",
            "session_id": event.session_id,
            "event_type": event.event_type,
            "event_data": event.event_data,
        }
    if isinstance(event, ToolResultEvent):
        return {
            "type": "tool_result",
            "tool_use_id": event.tool_call_id,
            "output": event.output,
            "is_error": event.is_error,
        }
    if isinstance(event, OTurnComplete):
        return {
            "type": "turn_complete",
            "input_tokens": event.input_tokens,
            "output_tokens": event.output_tokens,
        }
    if isinstance(event, ErrorEvent):
        return {"type": "error", "error": event.error, "detail": event.detail}
    return {"type": "unknown"}
