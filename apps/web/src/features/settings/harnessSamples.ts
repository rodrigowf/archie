/**
 * Realistic harness catalogs (`GET /api/config/harnesses`) for the gallery and the tests: the
 * Claude and Gemini rows are trimmed captures of the backend's catalogs (2026-10-07); Qwen and
 * Codex follow their loaders. Same data as `mock-server/data/harnesses.json`.
 */
import type { HarnessInfo } from '@/services';

export const HARNESS_SAMPLES: HarnessInfo[] = [
  {
    "id": "claude",
    "label": "Claude Code",
    "description": "Anthropic Claude Code CLI",
    "catalog": {
      "provider": "claude",
      "models": [
        {
          "id": "default",
          "label": "Default",
          "source": "builtin",
          "description": "Alias → Claude Sonnet 5.5",
          "context_window": 1000000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high",
            "xhigh",
            "max"
          ]
        },
        {
          "id": "sonnet",
          "label": "Sonnet",
          "source": "builtin",
          "description": "Alias → Claude Sonnet 5.5",
          "context_window": 1000000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high",
            "xhigh",
            "max"
          ]
        },
        {
          "id": "opus",
          "label": "Opus",
          "source": "builtin",
          "description": "Alias → Claude Opus 5.5",
          "context_window": 1000000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high",
            "xhigh",
            "max"
          ]
        },
        {
          "id": "haiku",
          "label": "Haiku",
          "source": "builtin",
          "description": "Alias → Claude Haiku 4.5",
          "context_window": 200000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": []
        },
        {
          "id": "claude-haiku-5-5",
          "label": "Claude Haiku 5.5",
          "source": "live",
          "context_window": 1000000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high",
            "xhigh",
            "max"
          ]
        },
        {
          "id": "claude-sonnet-5-5",
          "label": "Claude Sonnet 5.5",
          "source": "live",
          "context_window": 1000000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high",
            "xhigh",
            "max"
          ]
        },
        {
          "id": "claude-opus-5-5",
          "label": "Claude Opus 5.5",
          "source": "live",
          "context_window": 1000000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high",
            "xhigh",
            "max"
          ]
        },
        {
          "id": "claude-sonnet-4-6",
          "label": "Claude Sonnet 4.6",
          "source": "live",
          "context_window": 1000000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high",
            "max"
          ]
        },
        {
          "id": "claude-opus-4-6",
          "label": "Claude Opus 4.6",
          "source": "live",
          "context_window": 1000000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high",
            "max"
          ]
        },
        {
          "id": "claude-opus-4-5-20251101",
          "label": "Claude Opus 4.5",
          "source": "live",
          "context_window": 200000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high"
          ]
        },
        {
          "id": "claude-haiku-4-5-20251001",
          "label": "Claude Haiku 4.5",
          "source": "live",
          "context_window": 200000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": []
        }
      ],
      "options": [
        {
          "key": "effort",
          "label": "Reasoning effort",
          "kind": "select",
          "choices": [
            {
              "value": "low",
              "label": "Low"
            },
            {
              "value": "medium",
              "label": "Medium"
            },
            {
              "value": "high",
              "label": "High"
            },
            {
              "value": "xhigh",
              "label": "Extra high"
            },
            {
              "value": "max",
              "label": "Max"
            }
          ],
          "help": "--effort. Each model lists the levels it accepts (Claude 4.6 has no xhigh; Haiku/Sonnet 4.5 have no effort control). An unsupported level is lowered to the nearest one the model accepts. The CLI turns xhigh/max into high when thinking is disabled."
        },
        {
          "key": "thinking",
          "label": "Thinking",
          "kind": "select",
          "choices": [
            {
              "value": "adaptive",
              "label": "Adaptive",
              "description": "The model decides when and how much to think (4.6 and newer)."
            },
            {
              "value": "enabled",
              "label": "Fixed budget",
              "description": "Think up to 'Thinking budget' tokens (Claude 4.5/4.6; newer models use adaptive instead).",
              "models": [
                "claude-opus-4-6",
                "claude-sonnet-4-6",
                "claude-opus-4-5-20251101",
                "claude-haiku-4-5-20251001",
                "haiku"
              ]
            },
            {
              "value": "disabled",
              "label": "Off",
              "description": "No extended thinking."
            }
          ],
          "help": "--thinking. Hidden for models that always think adaptively (Opus 5.5, Sonnet 5.5, Fable 5.x). A mode the model lacks is mapped to the closest one it has. Thinking text is always requested as summaries.",
          "models": [
            "haiku",
            "claude-haiku-5-5",
            "claude-sonnet-4-6",
            "claude-opus-4-6",
            "claude-opus-4-5-20251101",
            "claude-haiku-4-5-20251001"
          ]
        },
        {
          "key": "thinking_budget",
          "label": "Thinking budget",
          "kind": "number",
          "default": 16000,
          "help": "Max thinking tokens (--max-thinking-tokens). Only used with Thinking = Fixed budget.",
          "models": [
            "haiku",
            "claude-sonnet-4-6",
            "claude-opus-4-6",
            "claude-opus-4-5-20251101",
            "claude-haiku-4-5-20251001"
          ],
          "min": 1024,
          "max": 128000,
          "step": 1024
        },
        {
          "key": "fallback_model",
          "label": "Fallback model",
          "kind": "select",
          "choices": [
            {
              "value": "default",
              "label": "Default"
            },
            {
              "value": "sonnet",
              "label": "Sonnet"
            },
            {
              "value": "opus",
              "label": "Opus"
            },
            {
              "value": "haiku",
              "label": "Haiku"
            },
            {
              "value": "claude-haiku-5-5",
              "label": "Claude Haiku 5.5"
            },
            {
              "value": "claude-sonnet-5-5",
              "label": "Claude Sonnet 5.5"
            },
            {
              "value": "claude-opus-5-5",
              "label": "Claude Opus 5.5"
            },
            {
              "value": "claude-sonnet-4-6",
              "label": "Claude Sonnet 4.6"
            },
            {
              "value": "claude-opus-4-6",
              "label": "Claude Opus 4.6"
            },
            {
              "value": "claude-opus-4-5-20251101",
              "label": "Claude Opus 4.5"
            },
            {
              "value": "claude-haiku-4-5-20251001",
              "label": "Claude Haiku 4.5"
            }
          ],
          "help": "--fallback-model: used when the main model is overloaded. Ignored if it equals the main model."
        },
        {
          "key": "todo_tools",
          "label": "Checklist tools",
          "kind": "toggle",
          "default": true,
          "help": "Offer the TodoWrite/Task checklist tools on every model (CLAUDE_CODE_ENABLE_TODO_TOOLS=1). Claude Code hides them on 4.8 / 5.x models unless this is on; Archie's chat renders them as checklist cards."
        }
      ],
      "default_model": "claude-sonnet-5-5",
      "allow_custom_model": true,
      "warnings": []
    }
  },
  {
    "id": "qwen",
    "label": "Qwen Code",
    "description": "Qwen Code CLI",
    "catalog": {
      "provider": "qwen",
      "models": [
        {
          "id": "qwen3.6-plus",
          "label": "Qwen3.6 Plus",
          "source": "settings",
          "description": "DashScope (Model Studio)",
          "context_window": 1000000,
          "supports_thinking": true,
          "supports_vision": true
        },
        {
          "id": "qwen3-coder-plus",
          "label": "Qwen3 Coder Plus",
          "source": "settings",
          "context_window": 1000000,
          "supports_thinking": true
        },
        {
          "id": "qwen3-coder-flash",
          "label": "Qwen3 Coder Flash",
          "source": "settings",
          "context_window": 1000000,
          "supports_thinking": false
        },
        {
          "id": "deepseek-v4",
          "label": "DeepSeek V4",
          "source": "settings",
          "description": "OpenAI-compatible provider from ~/.qwen/settings.json",
          "context_window": 128000,
          "supports_thinking": true
        }
      ],
      "options": [
        {
          "key": "thinking",
          "label": "Thinking",
          "kind": "toggle",
          "default": true,
          "help": "enable_thinking on the provider request. Models that cannot think ignore it."
        },
        {
          "key": "thinking_budget",
          "label": "Thinking budget (tokens)",
          "kind": "number",
          "help": "Max reasoning tokens per turn (thinking_budget). Only used while Thinking is on.",
          "min": 1,
          "max": 32768,
          "step": 1
        }
      ],
      "default_model": "qwen3.6-plus",
      "allow_custom_model": true,
      "warnings": []
    }
  },
  {
    "id": "gemini",
    "label": "Gemini CLI",
    "description": "Google Gemini CLI",
    "catalog": {
      "provider": "gemini",
      "models": [
        {
          "id": "auto",
          "label": "Auto",
          "source": "builtin",
          "description": "CLI router picks Gemini 3.1 Pro or a Flash model per turn",
          "supports_thinking": true
        },
        {
          "id": "pro",
          "label": "Pro (alias)",
          "source": "builtin",
          "description": "Resolves to gemini-3.1-pro-preview",
          "supports_thinking": true
        },
        {
          "id": "flash",
          "label": "Flash (alias)",
          "source": "builtin",
          "description": "Resolves to gemini-3-flash-preview",
          "supports_thinking": true
        },
        {
          "id": "gemini-3.1-pro-preview",
          "label": "Gemini 3.1 Pro Preview",
          "source": "builtin",
          "context_window": 1048576,
          "supports_thinking": true,
          "supports_vision": true
        },
        {
          "id": "gemini-3-flash-preview",
          "label": "Gemini 3 Flash Preview",
          "source": "builtin",
          "context_window": 1048576,
          "supports_thinking": true,
          "supports_vision": true
        },
        {
          "id": "gemini-2.5-pro",
          "label": "Gemini 2.5 Pro",
          "source": "builtin",
          "description": "May be unavailable to newer API keys",
          "context_window": 1048576,
          "supports_thinking": true,
          "supports_vision": true
        },
        {
          "id": "gemini-2.5-flash",
          "label": "Gemini 2.5 Flash",
          "source": "builtin",
          "context_window": 1048576,
          "supports_thinking": true,
          "supports_vision": true
        }
      ],
      "options": [
        {
          "key": "thinking_level",
          "label": "Thinking level",
          "kind": "select",
          "choices": [
            {
              "value": "minimal",
              "label": "Minimal",
              "description": "Least thinking (Flash / Flash-Lite only)"
            },
            {
              "value": "low",
              "label": "Low"
            },
            {
              "value": "medium",
              "label": "Medium"
            },
            {
              "value": "high",
              "label": "High",
              "description": "The CLI's default"
            }
          ],
          "default": "high",
          "help": "Gemini 3 thinkingLevel. A level a model does not accept is clamped to the nearest one it does (3 Pro: low/high; 3.1 Pro, 3.7/3.8 Flash: low–high).",
          "models": [
            "auto",
            "pro",
            "flash",
            "gemini-3.1-pro-preview",
            "gemini-3-flash-preview"
          ]
        },
        {
          "key": "thinking_budget",
          "label": "Thinking budget (tokens)",
          "kind": "number",
          "default": 8192,
          "help": "Gemini 2.5 thinkingBudget: -1 = dynamic, 0 = off (Flash only; 2.5 Pro minimum 128). Clamped to the model's range.",
          "models": [
            "gemini-2.5-pro",
            "gemini-2.5-flash"
          ],
          "min": -1,
          "max": 32768,
          "step": 1
        },
        {
          "key": "approval_mode",
          "label": "Tool approval",
          "kind": "select",
          "choices": [
            {
              "value": "yolo",
              "label": "Run every tool",
              "description": "Archie's default"
            },
            {
              "value": "auto_edit",
              "label": "Edits only",
              "description": "File edits run; shell and other tools are denied"
            },
            {
              "value": "plan",
              "label": "Plan (read-only)",
              "description": "Read-only planning mode"
            },
            {
              "value": "default",
              "label": "Read-only",
              "description": "Anything that would need approval is denied"
            }
          ],
          "default": "yolo",
          "help": "--approval-mode. Headless runs cannot ask, so 'ask' decisions become 'deny'."
        }
      ],
      "default_model": "auto",
      "allow_custom_model": true,
      "warnings": []
    }
  },
  {
    "id": "codex",
    "label": "Codex",
    "description": "OpenAI Codex CLI (app-server)",
    "catalog": {
      "provider": "codex",
      "models": [
        {
          "id": "gpt-6-luna",
          "label": "GPT-6-Luna",
          "source": "cli",
          "description": "Fast and affordable model for easier tasks.",
          "context_window": 272000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high",
            "xhigh",
            "max"
          ],
          "default_effort": "medium"
        },
        {
          "id": "gpt-5.6-terra",
          "label": "GPT-5.6-Terra",
          "source": "cli",
          "description": "Older balanced model for straightforward work.",
          "context_window": 272000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high",
            "xhigh",
            "max",
            "ultra"
          ],
          "default_effort": "medium"
        },
        {
          "id": "gpt-5.6-luna",
          "label": "GPT-5.6-Luna",
          "source": "cli",
          "description": "Older fast and efficient model.",
          "context_window": 272000,
          "supports_thinking": true,
          "supports_vision": true,
          "efforts": [
            "low",
            "medium",
            "high",
            "xhigh",
            "max"
          ],
          "default_effort": "medium"
        },
        {
          "id": "codex-mini",
          "label": "Codex Mini",
          "source": "cli",
          "description": "Small model tuned for quick edits.",
          "context_window": 200000,
          "supports_thinking": true,
          "supports_vision": false,
          "efforts": [
            "low",
            "medium",
            "high"
          ],
          "default_effort": "low"
        }
      ],
      "options": [
        {
          "key": "effort",
          "label": "Reasoning effort",
          "kind": "select",
          "choices": [
            {
              "value": "low",
              "label": "Low"
            },
            {
              "value": "medium",
              "label": "Medium"
            },
            {
              "value": "high",
              "label": "High"
            },
            {
              "value": "xhigh",
              "label": "Extra high"
            },
            {
              "value": "max",
              "label": "Max"
            },
            {
              "value": "ultra",
              "label": "Ultra"
            }
          ],
          "default": "medium",
          "help": "Reasoning effort per turn (turn/start effort). Levels depend on the model; unset uses the model's default (medium)."
        },
        {
          "key": "reasoning_summary",
          "label": "Reasoning summary",
          "kind": "select",
          "choices": [
            {
              "value": "auto",
              "label": "Auto"
            },
            {
              "value": "concise",
              "label": "Concise"
            },
            {
              "value": "detailed",
              "label": "Detailed"
            },
            {
              "value": "none",
              "label": "None",
              "description": "No thinking shown in the UI"
            }
          ],
          "default": "concise",
          "help": "How much of the model's reasoning Codex summarizes into the thinking stream. Codex models default to none; when unset Archie sends concise."
        },
        {
          "key": "verbosity",
          "label": "Verbosity",
          "kind": "select",
          "choices": [
            {
              "value": "low",
              "label": "Low"
            },
            {
              "value": "medium",
              "label": "Medium"
            },
            {
              "value": "high",
              "label": "High"
            }
          ],
          "default": "low",
          "help": "Length of the model's answers (config model_verbosity). Unset uses the model default (low)."
        },
        {
          "key": "web_search",
          "label": "Web search",
          "kind": "select",
          "choices": [
            {
              "value": "disabled",
              "label": "Disabled"
            },
            {
              "value": "cached",
              "label": "Cached",
              "description": "Search an OpenAI-maintained index (no live fetches)"
            },
            {
              "value": "live",
              "label": "Live",
              "description": "Fetch live results"
            }
          ],
          "help": "Codex's built-in web_search tool (config web_search). Unset leaves Codex's default."
        }
      ],
      "default_model": "gpt-6-luna",
      "allow_custom_model": true,
      "warnings": [
        "Codex is using the shared login in ~/.codex (also used by VS Code / the codex TUI) and its sessions stay there. For a dedicated Archie login run: CODEX_HOME=~/.codex-archie codex login --device-auth"
      ]
    }
  }
];
