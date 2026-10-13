"""``voice_start`` takes the Settings → Voice defaults on every path that
builds a voice connection.

Regression (2026-10-13): after a backend restart the Archie conversation
came back as a text session; the web app's ``voice_start`` names no
provider, and the text→voice re-arm path passed ``None`` straight to
``restart_voice`` → the registry's hard-coded ``openai`` default — so
Gemini was configured but the call went to OpenAI Realtime (and failed on
its 16,384-token instructions cap).
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from api.routes import orchestrator as route

APP_CFG = {
    "default_voice_provider": "google",
    "default_voice_model": "gemini-3.1-flash-live-preview",
    "default_voice_name": "Puck",
    "default_voice_transcription_language": "",
    "default_voice_endpoint": "aistudio",
}


def _cfg(cfg=APP_CFG):
    return patch("api.routes.config._load_config", return_value=dict(cfg))


def test_fill_all_missing_uses_saved_defaults():
    with _cfg():
        got = route._fill_voice_defaults(None, None, None, None, None)
    assert got == ("google", "gemini-3.1-flash-live-preview", "Puck", "", "aistudio")


def test_fill_keeps_explicit_fields():
    with _cfg():
        got = route._fill_voice_defaults("google", "m", "Kore", "pt", "vertex")
    assert got == ("google", "m", "Kore", "pt", "vertex")


def test_other_provider_does_not_get_the_default_providers_model():
    """A client asking for Qwen must not be handed Gemini's model/voice ids;
    ``resolve_voice_target`` then picks Qwen's own defaults."""
    with _cfg():
        got = route._fill_voice_defaults("qwen", None, None, None, None)
    assert got == ("qwen", None, None, "", "aistudio")


def test_config_load_failure_leaves_request_unchanged():
    with patch("api.routes.config._load_config", side_effect=OSError("boom")):
        got = route._fill_voice_defaults(None, None, None, None, None)
    assert got == (None, None, None, None, None)


@pytest.mark.asyncio
async def test_rearm_on_text_session_uses_configured_provider():
    """The failing path: voice_start (no provider) on an open text session."""
    session = MagicMock()
    session.is_voice = False
    session.voice_is_ending = False
    session.restart_voice = AsyncMock()
    session.jsonl_id = "j"
    session.get_model_info.return_value = {}

    pool = MagicMock()
    pool.await_orchestrator_stop = AsyncMock(return_value=True)
    pool.has_orchestrator.return_value = True
    pool.orchestrator_id = "L1"
    pool.get_orchestrator.return_value = session

    with _cfg(), \
            patch.object(route, "_attach_voice_payload", AsyncMock()), \
            patch.object(route, "_safe_send_bytes", AsyncMock()):
        got, subscribed = await route._handle_start(
            MagicMock(), pool, {"type": "voice_start", "local_id": "L1"}, voice=True,
        )

    assert got is session and subscribed is True
    kwargs = session.restart_voice.await_args.kwargs
    assert kwargs["voice_provider"] == "google"
    assert kwargs["voice_model"] == "gemini-3.1-flash-live-preview"
    assert kwargs["voice_name"] == "Puck"
    assert kwargs["voice_endpoint"] == "aistudio"


@pytest.mark.asyncio
async def test_reattach_to_live_call_does_not_fill_defaults():
    """Re-attaching to a LIVE call with omitted fields is "no change": the
    defaults must not be filled in, or they would read as drift."""
    session = MagicMock()
    session.is_voice = True
    session.voice_is_ending = False
    session.voice_config_drifts_from.return_value = None
    session.jsonl_id = "j"
    session.get_model_info.return_value = {}

    pool = MagicMock()
    pool.await_orchestrator_stop = AsyncMock(return_value=True)
    pool.has_orchestrator.return_value = True
    pool.orchestrator_id = "L1"
    pool.get_orchestrator.return_value = session

    with _cfg(), \
            patch.object(route, "_attach_voice_payload", AsyncMock()), \
            patch.object(route, "_safe_send_bytes", AsyncMock()):
        await route._handle_start(
            MagicMock(), pool, {"type": "voice_start", "local_id": "L1"}, voice=True,
        )

    kwargs = session.voice_config_drifts_from.call_args.kwargs
    assert kwargs["provider"] is None and kwargs["model"] is None
