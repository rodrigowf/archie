"""Tests for api/serializers.py — Event to dict conversion."""

from api.serializers import serialize_event
from manager.types import (
    CompactComplete,
    Event,
    TextComplete,
    TextDelta,
    ThinkingComplete,
    ThinkingDelta,
    ToolResult,
    ToolUse,
    TurnComplete,
)


class TestSerializeEvent:
    def test_text_delta(self):
        result = serialize_event(TextDelta(text="hello"))
        assert result == {"type": "text_delta", "text": "hello"}

    def test_text_complete(self):
        result = serialize_event(TextComplete(text="full text"))
        assert result == {"type": "text_complete", "text": "full text"}

    def test_thinking_delta(self):
        result = serialize_event(ThinkingDelta(text="hmm"))
        assert result == {"type": "thinking_delta", "text": "hmm"}

    def test_thinking_complete(self):
        result = serialize_event(ThinkingComplete(text="thought"))
        assert result == {"type": "thinking_complete", "text": "thought"}

    def test_tool_use(self):
        result = serialize_event(ToolUse(
            tool_use_id="t1",
            tool_name="Bash",
            tool_input={"command": "ls"},
        ))
        assert result == {
            "type": "tool_use",
            "tool_use_id": "t1",
            "tool_name": "Bash",
            "tool_input": {"command": "ls"},
        }

    def test_tool_result(self):
        result = serialize_event(ToolResult(
            tool_use_id="t1",
            output="file.txt",
            is_error=False,
        ))
        assert result == {
            "type": "tool_result",
            "tool_use_id": "t1",
            "output": "file.txt",
            "is_error": False,
        }

    def test_tool_result_error(self):
        result = serialize_event(ToolResult(
            tool_use_id="t1",
            output="not found",
            is_error=True,
        ))
        assert result["is_error"] is True

    def test_turn_complete(self):
        result = serialize_event(TurnComplete(
            cost=0.05,
            usage={"input_tokens": 100},
            num_turns=1,
            session_id="s1",
            is_error=False,
            result="done",
        ))
        # Subset-match: the serializer may add helper fields like
        # ``input_tokens``/``output_tokens`` (flattened from ``usage``) that
        # this test doesn't need to pin down.  Check the load-bearing
        # fields explicitly instead of comparing the whole dict.
        assert result["type"] == "turn_complete"
        assert result["cost"] == 0.05
        assert result["usage"] == {"input_tokens": 100}
        assert result["num_turns"] == 1
        assert result["session_id"] == "s1"
        assert result["is_error"] is False
        assert result["result"] == "done"

    def test_turn_complete_defaults(self):
        result = serialize_event(TurnComplete())
        assert result["type"] == "turn_complete"
        assert result["cost"] is None
        assert result["num_turns"] == 0

    def test_input_tokens_prefers_context_tokens(self):
        """``input_tokens`` on the wire is the context size, not the spend.

        ``usage`` is cumulative over the turn's requests, so its sum
        (48 588 here) is ~3× the real context the ring should divide by.
        """
        result = serialize_event(TurnComplete(
            usage={
                "input_tokens": 6,
                "cache_read_input_tokens": 32_286,
                "cache_creation_input_tokens": 16_296,
                "output_tokens": 300,
            },
            context_tokens=16_298,
        ))
        assert result["input_tokens"] == 16_298
        assert result["output_tokens"] == 300
        # The raw blob still travels untouched for anyone accounting spend.
        assert result["usage"]["cache_read_input_tokens"] == 32_286

    def test_context_window_travels_only_when_known(self):
        """TOK-2: absent means "keep the window session_started gave you"."""
        assert "context_window" not in serialize_event(TurnComplete())
        with_window = serialize_event(TurnComplete(context_window=1_000_000))
        assert with_window["context_window"] == 1_000_000

    def test_input_tokens_falls_back_to_the_usage_sum(self):
        """Harnesses that report one request's usage need no context_tokens."""
        result = serialize_event(TurnComplete(
            usage={
                "input_tokens": 1_200,
                "cache_read_input_tokens": 8_000,
                "output_tokens": 40,
            },
        ))
        assert result["input_tokens"] == 9_200

    def test_compact_complete(self):
        result = serialize_event(CompactComplete(trigger="auto"))
        # The serializer also exposes the ``summary`` field (empty by
        # default) for the divider rendering in the UI; pin only the
        # fields callers depend on.
        assert result["type"] == "compact_complete"
        assert result["trigger"] == "auto"

    def test_compact_complete_default(self):
        result = serialize_event(CompactComplete())
        assert result["trigger"] == "manual"

    def test_unknown_event(self):
        result = serialize_event(Event())
        assert result == {"type": "unknown"}
