"""ChatGPT import — a read-only provider, not a runnable harness.

Conversations converted from a ChatGPT data export by
``shared/scripts/chatgpt_import.py`` into ``context/chatgpt-history/jsonl/``. This package
contributes a :class:`~manager.protocol.ProviderAdapter` so :class:`~manager.store.SessionStore`
can list and read them, and deliberately **no** ``HarnessSpec``: there is no CLI, no session
manager and nothing to spawn or resume. See ``docs/harnesses/chatgpt-import.md``.
"""

from .adapter import ChatGptAdapter

__all__ = ["ChatGptAdapter"]
