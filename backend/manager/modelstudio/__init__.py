"""Model Studio harness — Claude Code driven against Alibaba Model Studio.

Runs the same bundled Claude Code CLI as :mod:`manager.claude` (subclassed
session manager, same JSONL in ``context/<id>.jsonl``) with the CLI pointed
at Model Studio's Anthropic-compatible endpoint, so GLM, DeepSeek, Kimi and
Qwen models get Claude Code's agent loop.  Registration lives in
:mod:`manager.modelstudio.adapter`; this package stays import-light.
"""
