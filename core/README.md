# amsha-core

Core engine for Amsha Agent. Contains the agent loop, reasoning engines, model routing,
reflexes, safety guards, and tool interfaces.

## Module Extraction Status

Under the `extract-core-subsystems` program (see
[`docs/adr/architecture/ADR-extract-core-subsystems.md`](../docs/adr/architecture/ADR-extract-core-subsystems.md)),
large shared surfaces moved out of this module. Contracts consumed from here now live in dedicated API jars,
and heavy implementations moved into tier-2 modules:

| Extracted surface | New home |
|---|---|
| Shared utilities (`AmshaException`, `TemporalUtils`, `PathSanitizer`, `BackoffRetryExecutor`, ...) | `common` |
| LLM exception taxonomy + `ChatModelCreationPort` | `llm-provider-api` |
| Persistence contract types (`TieredBackend`, `CronStatus`, `CheckpointStatus`, scope probes) | `persistence-api` |
| Security vocabulary (`SecretVault`, `VaultKey`, `ToolOperation`/`RiskLevel`, crypto constants) | `security-api` |
| Vault / crypto (Argon2id, AES-GCM-SIV) / governed HTTP implementation | `security` |
| `AgentTool` contract surface (`AgentTool`, `ToolResponse`, `ToolExecutionContext`, ...) | `tool-api` |
| Shadow-classifier contracts | `classifier-api` |
| Tiered memory services (`CoreMemoryService`, recall/archival, `TieredMemoryProducer`) | `memory` |
| Skill loading machinery (`SkillLoader`, providers, admission, file watcher) | `skills` |

What remains here: agent loop and state machine, reasoning engines, model routing, reflexes, pipeline,
governance/HITL coordination, audit, skill registry + entry contracts (`core.skill`), and tool discovery.
BouncyCastle and commonmark dependencies left `core/pom.xml` with their owners.

## LLM Providers

LLM provider SPIs (`AgentChatModelProvider`, `RoutingChatModelProvider`, `ChatModelCreationPort`)
are defined in `amsha-llm-provider-api`. All 21 concrete provider implementations reside in
`amsha-llm-providers`. `amsha-core` consumes providers decoupled via `ChatModelCreationPort`
and CDI `Instance<T>` multi-bean discovery.

### Supported Providers

| Provider | Provider ID | Default Model | Default Base URL |
|---|---|---|---|
| Groq | `groq` | `meta-llama/llama-4-scout-17b-16e-instruct` | `https://api.groq.com/openai/v1` |
| OpenAI | `openai` | `gpt-4o-mini` | `https://api.openai.com/v1` |
| Ollama | `ollama` | `mistral-small:latest` | `http://localhost:11434` |
| OpenRouter | `openrouter` | `minimax/minimax-m3:free` | `https://openrouter.ai/api/v1` |
| NVIDIA | `nvidia` | `meta/llama-3.2-11b-vision-instruct` | `https://integrate.api.nvidia.com/v1` |
| Cerebras | `cerebras` | `qwen-3.8-27b` | `https://api.cerebras.ai/v1` |
| Sarvam AI | `sarvam` | `sarvam-105b-conversations` | `https://api.sarvam.ai/v1` |
| OpenCode | `opencode` | `deepseek-v4-flash-free` | `https://opencode.ai/zen/v1` |
| Hugging Face | `huggingface` | `meta-llama/Llama-3.3-70B-Instruct` | `https://router.huggingface.co/v1` |
| Mistral AI | `mistral` | `codestral-latest` | `https://api.mistral.ai/v1` |
| Cloudflare Workers AI | `cloudflare` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1` |
| Cohere | `cohere` | `command-r-08-2024` | `https://api.cohere.ai/compatibility/v1` |
| Codex CLI | `codex` | `gpt-5.6-luna` | `https://chatgpt.com/backend-api/codex` |
| Claude Code | `claude-code` | `claude-sonnet-4-5` | `https://api.anthropic.com` |
| Antigravity CLI | `agy` | `gemini-3.1-flash-lite` | `https://generativelanguage.googleapis.com/v1beta/openai` |
| Kimchi | `kimchi` | `minimax-m3` | `https://llm.kimchi.dev/openai/v1` |
| Foundry Local | `foundry` | `qwen3-14b` | `http://127.0.0.1:8000/v1` (or dynamic daemon port) |
| Gemini | `gemini` | `gemini-3.1-flash-lite` | `https://generativelanguage.googleapis.com/v1beta` |
| OMLX | `omlx` | `mlx-community/Qwen3.6-35B-A3B-4bit` | `http://127.0.0.1:8000/v1` |
| Llama.cpp | `llamacpp` | `qwen3` | `http://127.0.0.1:9000/v1` |
| Generic OpenAI | `generic-openai` | `gpt-4o-mini` | *(Configured via `AMSHA_GENERIC_OPENAI_BASE_URL`)* |

## Sarvam AI Configuration

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.sarvam.api-key` | `AMSHA_SARVAM_API_KEY` | — | Sarvam AI API key |
| `amsha.llm.sarvam.base-url` | `AMSHA_SARVAM_BASE_URL` | `https://api.sarvam.ai/v1` | Sarvam AI v1 API base URL |
| `amsha.llm.sarvam.model` | `AMSHA_SARVAM_MODEL` | `sarvam-105b-conversations` | Default chat model |
| `amsha.compaction.sarvam.api-key` | `AMSHA_COMPACTION_SARVAM_API_KEY` | — | Sarvam API key for compaction slot |
| `amsha.compaction.sarvam.model` | `AMSHA_COMPACTION_SARVAM_MODEL` | `sarvam-105b-conversations` | Compaction slot model |
| `amsha.compaction.sarvam.base-url` | `AMSHA_COMPACTION_SARVAM_BASE_URL` | `https://api.sarvam.ai/v1` | Compaction slot base URL |
| `amsha.classifier.sarvam.api-key` | `AMSHA_CLASSIFIER_SARVAM_API_KEY` | — | Sarvam API key for classifier slot |
| `amsha.classifier.sarvam.model` | `AMSHA_CLASSIFIER_SARVAM_MODEL` | `sarvam-105b-conversations` | Classifier slot model |
| `amsha.classifier.sarvam.base-url` | `AMSHA_CLASSIFIER_SARVAM_BASE_URL` | `https://api.sarvam.ai/v1` | Classifier slot base URL |

## OpenCode Configuration

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.opencode.api-key` | `AMSHA_OPENCODE_API_KEY` | — | OpenCode API key |
| `amsha.llm.opencode.base-url` | `AMSHA_OPENCODE_BASE_URL` | `https://opencode.ai/zen/v1` | OpenCode API base URL (Zen: `.../zen/v1`, Go: `.../zen/go/v1`) |
| `amsha.llm.opencode.model` | `AMSHA_OPENCODE_MODEL` | `deepseek-v4-flash-free` | Default chat model |
| `amsha.compaction.opencode.api-key` | `AMSHA_COMPACTION_OPENCODE_API_KEY` | — | OpenCode API key for compaction slot |
| `amsha.compaction.opencode.model` | `AMSHA_COMPACTION_OPENCODE_MODEL` | `deepseek-v4-flash-free` | Compaction slot model |
| `amsha.compaction.opencode.base-url` | `AMSHA_COMPACTION_OPENCODE_BASE_URL` | `https://opencode.ai/zen/v1` | Compaction slot base URL |
| `amsha.classifier.opencode.api-key` | `AMSHA_CLASSIFIER_OPENCODE_API_KEY` | — | OpenCode API key for classifier slot |
| `amsha.classifier.opencode.model` | `AMSHA_CLASSIFIER_OPENCODE_MODEL` | `deepseek-v4-flash-free` | Classifier slot model |
| `amsha.classifier.opencode.base-url` | `AMSHA_CLASSIFIER_OPENCODE_BASE_URL` | `https://opencode.ai/zen/v1` | Classifier slot base URL |

## Hugging Face Configuration

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.huggingface.api-key` | `AMSHA_HUGGINGFACE_API_KEY` | — | Hugging Face User Access Token (`hf_...`) |
| `amsha.llm.huggingface.base-url` | `AMSHA_HUGGINGFACE_BASE_URL` | `https://router.huggingface.co/v1` | Hugging Face Inference API / Router base URL |
| `amsha.llm.huggingface.model` | `AMSHA_HUGGINGFACE_MODEL` | `meta-llama/Llama-3.3-70B-Instruct` | Default chat model |
| `amsha.compaction.huggingface.api-key` | `AMSHA_COMPACTION_HUGGINGFACE_API_KEY` | — | Hugging Face API key for compaction slot |
| `amsha.compaction.huggingface.model` | `AMSHA_COMPACTION_HUGGINGFACE_MODEL` | `meta-llama/Llama-3.3-70B-Instruct` | Compaction slot model |
| `amsha.compaction.huggingface.base-url` | `AMSHA_COMPACTION_HUGGINGFACE_BASE_URL` | `https://router.huggingface.co/v1` | Compaction slot base URL |
| `amsha.classifier.huggingface.api-key` | `AMSHA_CLASSIFIER_HUGGINGFACE_API_KEY` | — | Hugging Face API key for classifier slot |
| `amsha.classifier.huggingface.model` | `AMSHA_CLASSIFIER_HUGGINGFACE_MODEL` | `meta-llama/Llama-3.3-70B-Instruct` | Classifier slot model |
| `amsha.classifier.huggingface.base-url` | `AMSHA_CLASSIFIER_HUGGINGFACE_BASE_URL` | `https://router.huggingface.co/v1` | Classifier slot base URL |

## Mistral AI Configuration

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.mistral.api-key` | `AMSHA_MISTRAL_API_KEY` | — | Mistral AI API key |
| `amsha.llm.mistral.base-url` | `AMSHA_MISTRAL_BASE_URL` | `https://api.mistral.ai/v1` | Mistral AI v1 API base URL |
| `amsha.llm.mistral.model` | `AMSHA_MISTRAL_MODEL` | `codestral-latest` | Default chat model |
| `amsha.compaction.mistral.api-key` | `AMSHA_COMPACTION_MISTRAL_API_KEY` | — | Mistral API key for compaction slot |
| `amsha.compaction.mistral.model` | `AMSHA_COMPACTION_MISTRAL_MODEL` | `codestral-latest` | Compaction slot model |
| `amsha.compaction.mistral.base-url` | `AMSHA_COMPACTION_MISTRAL_BASE_URL` | `https://api.mistral.ai/v1` | Compaction slot base URL |
| `amsha.classifier.mistral.api-key` | `AMSHA_CLASSIFIER_MISTRAL_API_KEY` | — | Mistral API key for classifier slot |
| `amsha.classifier.mistral.model` | `AMSHA_CLASSIFIER_MISTRAL_MODEL` | `codestral-latest` | Classifier slot model |
| `amsha.classifier.mistral.base-url` | `AMSHA_CLASSIFIER_MISTRAL_BASE_URL` | `https://api.mistral.ai/v1` | Classifier slot base URL |

## Cloudflare Workers AI Configuration

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.cloudflare.api-key` | `AMSHA_CLOUDFLARE_API_KEY` | — | Cloudflare API token |
| `amsha.llm.cloudflare.account-id` | `AMSHA_CLOUDFLARE_ACCOUNT_ID` | — | Cloudflare Account ID |
| `amsha.llm.cloudflare.base-url` | `AMSHA_CLOUDFLARE_BASE_URL` | `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1` | Workers AI v1 API base URL |
| `amsha.llm.cloudflare.unresolved-base-url` | — | same template | Template used as nested MP Config default (avoids `${ENV:…/{account_id}/…}` early-`}` close) |
| `amsha.llm.cloudflare.model` | `AMSHA_CLOUDFLARE_MODEL` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | Default chat model |
| `amsha.compaction.cloudflare.api-key` | `AMSHA_COMPACTION_CLOUDFLARE_API_KEY` | — | Cloudflare API token for compaction slot |
| `amsha.compaction.cloudflare.account-id` | `AMSHA_COMPACTION_CLOUDFLARE_ACCOUNT_ID` | — | Cloudflare Account ID for compaction slot |
| `amsha.compaction.cloudflare.model` | `AMSHA_COMPACTION_CLOUDFLARE_MODEL` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | Compaction slot model |
| `amsha.compaction.cloudflare.base-url` | `AMSHA_COMPACTION_CLOUDFLARE_BASE_URL` | `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1` | Compaction slot base URL |
| `amsha.compaction.structured-output-mode` | `AMSHA_COMPACTION_STRUCTURED_OUTPUT_MODE` | `PROMPT_DIRECTIVE` | Structured output mode for compaction |
| `amsha.classifier.cloudflare.api-key` | `AMSHA_CLASSIFIER_CLOUDFLARE_API_KEY` | — | Cloudflare API token for classifier slot |
| `amsha.classifier.cloudflare.account-id` | `AMSHA_CLASSIFIER_CLOUDFLARE_ACCOUNT_ID` | — | Cloudflare Account ID for classifier slot |
| `amsha.classifier.cloudflare.model` | `AMSHA_CLASSIFIER_CLOUDFLARE_MODEL` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | Classifier slot model |
| `amsha.classifier.cloudflare.base-url` | `AMSHA_CLASSIFIER_CLOUDFLARE_BASE_URL` | `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1` | Classifier slot base URL |

## Cohere Configuration

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.cohere.api-key` | `AMSHA_COHERE_API_KEY` | — | Cohere API key |
| `amsha.llm.cohere.base-url` | `AMSHA_COHERE_BASE_URL` | `https://api.cohere.ai/compatibility/v1` | Cohere OpenAI compatibility API base URL |
| `amsha.llm.cohere.model` | `AMSHA_COHERE_MODEL` | `command-r-08-2024` | Default chat model |
| `amsha.compaction.cohere.api-key` | `AMSHA_COMPACTION_COHERE_API_KEY` | — | Cohere API key for compaction slot |
| `amsha.compaction.cohere.model` | `AMSHA_COMPACTION_COHERE_MODEL` | `command-r-08-2024` | Compaction slot model |
| `amsha.compaction.cohere.base-url` | `AMSHA_COMPACTION_COHERE_BASE_URL` | `https://api.cohere.ai/compatibility/v1` | Compaction slot base URL |
| `amsha.classifier.cohere.api-key` | `AMSHA_CLASSIFIER_COHERE_API_KEY` | — | Cohere API key for classifier slot |
| `amsha.classifier.cohere.model` | `AMSHA_CLASSIFIER_COHERE_MODEL` | `command-r-08-2024` | Classifier slot model |
| `amsha.classifier.cohere.base-url` | `AMSHA_CLASSIFIER_COHERE_BASE_URL` | `https://api.cohere.ai/compatibility/v1` | Classifier slot base URL |

## Codex Configuration

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.codex.api-key` | `AMSHA_CODEX_API_KEY` | — | Optional direct API key / token (defaults to `~/.codex/auth.json` OAuth session) |
| `amsha.llm.codex.base-url` | `AMSHA_CODEX_BASE_URL` | `https://chatgpt.com/backend-api/codex` | Codex endpoint (defaults to a direct ChatGPT Plus Responses endpoint; or `http://127.0.0.1:8787/v1` for Headroom proxy, or `https://api.openai.com/v1`) |
| `amsha.llm.codex.model` | `AMSHA_CODEX_MODEL` | `gpt-5.6-luna` | Default chat model |
| `amsha.compaction.codex.api-key` | `AMSHA_COMPACTION_CODEX_API_KEY` | — | Codex API key for compaction slot |
| `amsha.compaction.codex.model` | `AMSHA_COMPACTION_CODEX_MODEL` | `gpt-5.6-luna` | Compaction slot model |
| `amsha.compaction.codex.base-url` | `AMSHA_COMPACTION_CODEX_BASE_URL` | `https://chatgpt.com/backend-api/codex` | Compaction slot base URL |
| `amsha.classifier.codex.api-key` | `AMSHA_CLASSIFIER_CODEX_API_KEY` | — | Codex API key for classifier slot |
| `amsha.classifier.codex.model` | `AMSHA_CLASSIFIER_CODEX_MODEL` | `gpt-5.6-luna` | Classifier slot model |
| `amsha.classifier.codex.base-url` | `AMSHA_CLASSIFIER_CODEX_BASE_URL` | `https://chatgpt.com/backend-api/codex` | Classifier slot base URL |

## Claude Code Configuration

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.claude-code.api-key` | `AMSHA_CLAUDE_CODE_API_KEY` | — | Optional direct API key / token (defaults to macOS Keychain or `~/.claude/.credentials.json`) |
| `amsha.llm.claude-code.base-url` | `AMSHA_CLAUDE_CODE_BASE_URL` | `https://api.anthropic.com` | Anthropic Messages endpoint (defaults to `https://api.anthropic.com`) |
| `amsha.llm.claude-code.model` | `AMSHA_CLAUDE_CODE_MODEL` | `claude-sonnet-4-5` | Default chat model |
| `amsha.compaction.claude-code.api-key` | `AMSHA_COMPACTION_CLAUDE_CODE_API_KEY` | — | Claude Code API key for compaction slot |
| `amsha.compaction.claude-code.model` | `AMSHA_COMPACTION_CLAUDE_CODE_MODEL` | `claude-sonnet-4-5` | Compaction slot model |
| `amsha.compaction.claude-code.base-url` | `AMSHA_COMPACTION_CLAUDE_CODE_BASE_URL` | `https://api.anthropic.com` | Compaction slot base URL |
| `amsha.classifier.claude-code.api-key` | `AMSHA_CLASSIFIER_CLAUDE_CODE_API_KEY` | — | Claude Code API key for classifier slot |
| `amsha.classifier.claude-code.model` | `AMSHA_CLASSIFIER_CLAUDE_CODE_MODEL` | `claude-sonnet-4-5` | Classifier slot model |
| `amsha.classifier.claude-code.base-url` | `AMSHA_CLASSIFIER_CLAUDE_CODE_BASE_URL` | `https://api.anthropic.com` | Classifier slot base URL |

## Antigravity (agy) Configuration

The `agy` provider supports two execution paths:
1. **HTTP Mode (OpenAI-compatible)**: Uses governed transport against `https://generativelanguage.googleapis.com/v1beta/openai` with full SSE streaming and native tool execution. Activated when an API key or unexpired OAuth session token is present.
2. **Local CLI Subprocess Mode**: Direct execution via local `agy` binary (`agy -p ... --output-format json`) leveraging your active Google user session with zero API key required.

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.agy.api-key` | `AMSHA_AGY_API_KEY` | — | Optional direct API key / token (defaults to macOS Keychain, `~/.gemini/*`, or local `agy` binary) |
| `amsha.llm.agy.base-url` | `AMSHA_AGY_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta/openai` | Gemini OpenAI-compatible endpoint |
| `amsha.llm.agy.model` | `AMSHA_AGY_MODEL` | `gemini-3.1-flash-lite` | Default chat model |
| `amsha.compaction.agy.api-key` | `AMSHA_COMPACTION_AGY_API_KEY` | — | Antigravity API key for compaction slot |
| `amsha.compaction.agy.model` | `AMSHA_COMPACTION_AGY_MODEL` | `gemini-3.1-flash-lite` | Compaction slot model |
| `amsha.compaction.agy.base-url` | `AMSHA_COMPACTION_AGY_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta/openai` | Compaction slot base URL |
| `amsha.classifier.agy.api-key` | `AMSHA_CLASSIFIER_AGY_API_KEY` | — | Antigravity API key for classifier slot |
| `amsha.classifier.agy.model` | `AMSHA_CLASSIFIER_AGY_MODEL` | `gemini-3.1-flash-lite` | Classifier slot model |
| `amsha.classifier.agy.base-url` | `AMSHA_CLASSIFIER_AGY_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta/openai` | Classifier slot base URL |

## Kimchi Configuration

The `kimchi` provider integrates with `https://llm.kimchi.dev/openai/v1` and automatically resolves credentials from `~/.config/kimchi/config.json`, `~/.config/opencode/opencode.json`, macOS Keychain, or `AMSHA_KIMCHI_API_KEY`.

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.kimchi.api-key` | `AMSHA_KIMCHI_API_KEY` | — | Optional direct API key (defaults to automatic resolution) |
| `amsha.llm.kimchi.base-url` | `AMSHA_KIMCHI_BASE_URL` | `https://llm.kimchi.dev/openai/v1` | Kimchi OpenAI-compatible endpoint |
| `amsha.llm.kimchi.model` | `AMSHA_KIMCHI_MODEL` | `minimax-m3` | Default chat model |
| `amsha.compaction.kimchi.api-key` | `AMSHA_COMPACTION_KIMCHI_API_KEY` | — | Kimchi API key for compaction slot |
| `amsha.compaction.kimchi.model` | `AMSHA_COMPACTION_KIMCHI_MODEL` | `minimax-m3` | Compaction slot model |
| `amsha.compaction.kimchi.base-url` | `AMSHA_COMPACTION_KIMCHI_BASE_URL` | `https://llm.kimchi.dev/openai/v1` | Compaction slot base URL |
| `amsha.classifier.kimchi.api-key` | `AMSHA_CLASSIFIER_KIMCHI_API_KEY` | — | Kimchi API key for classifier slot |
| `amsha.classifier.kimchi.model` | `AMSHA_CLASSIFIER_KIMCHI_MODEL` | `minimax-m3` | Classifier slot model |
| `amsha.classifier.kimchi.base-url` | `AMSHA_CLASSIFIER_KIMCHI_BASE_URL` | `https://llm.kimchi.dev/openai/v1` | Classifier slot base URL |

## Foundry Local Configuration

The `foundry` provider integrates with Microsoft Foundry Local daemon (`foundry server`) and CLI (`foundry complete`). It automatically discovers the running daemon port from `~/.foundry/daemon.json` and seamlessly falls back to CLI execution if the HTTP daemon is stopped.

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.foundry.base-url` | `AMSHA_FOUNDRY_BASE_URL` | `http://127.0.0.1:8000/v1` | Foundry Local HTTP API endpoint (or dynamic daemon port) |
| `amsha.llm.foundry.model` | `AMSHA_FOUNDRY_MODEL` | `qwen3-14b` | Default chat model |
| `amsha.compaction.foundry.model` | `AMSHA_COMPACTION_FOUNDRY_MODEL` | `qwen3-14b` | Compaction slot model |
| `amsha.compaction.foundry.base-url` | `AMSHA_COMPACTION_FOUNDRY_BASE_URL` | `http://127.0.0.1:8000/v1` | Compaction slot base URL |
| `amsha.classifier.foundry.model` | `AMSHA_CLASSIFIER_FOUNDRY_MODEL` | `qwen3-14b` | Classifier slot model |
| `amsha.classifier.foundry.base-url` | `AMSHA_CLASSIFIER_FOUNDRY_BASE_URL` | `http://127.0.0.1:8000/v1` | Classifier slot base URL |

## Observability & ChatModelListener Pilot

Amsha provides zero-payload-leakage observability for LLM invocations with strict runtime controls. For this pilot, `AmshaChatModelListener` attaches to OpenAI-compatible `OpenAiChatModel` instances:
- `off` (default): Request acquisition rejected; no listener telemetry is emitted.
- `shadow`: Emits shadow metrics (`amsha.observability.listener.shadow.*`) and duration timers for zero-delta parity comparison against authoritative `AgentObserver` metrics; no sample queue is retained.
- `active`: Emits active `amsha.llm.listener_call` child spans and active metrics/timers for calls without an existing manual instrumentation marker. Calls already covered by Amsha's manual instrumentation remain authoritative and are not duplicated.

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.observability.chat-listener.mode` | `AMSHA_OBSERVABILITY_CHAT_LISTENER_MODE` | `off` | Mode for LangChain4j `ChatModelListener` pilot (`off`, `shadow`, `active`) |

## Decision Plane Shadow Classifiers (Issues 04 & 07)

Observational-only shadow evaluation for intent routing, tool risk, and answer quality. Shadow evaluation operates in parallel to authoritative components without mutating live routing, tool authorization, HITL decisions, or retry budgets. Disabled by default.

> **Deferred — enabling a switch does not activate the plane.** No production `DecisionEngine` bean is
> registered in this build, so enabled shadow classifiers return `NoOpinion(MISSING_MODEL)` and disabled
> classifiers return `DISABLED`. The shadow switches below request *observation only*, and `DecisionPlaneReadiness` logs exactly one `WARN` at
> startup naming the enabled switches — its absence on an enabled deployment is a defect, not a no-op. The
> intent decision router is gated twice: `isEnabled()` additionally requires an available engine, and a policy
> action requires `isActivationGateApproved()`, so setting
> `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ENABLED=true` on its own is intentionally a no-op. See
> [ADR-jev-decision-plane](../docs/adr/architecture/ADR-jev-decision-plane.md) and
> [Architecture Invariants](../docs/Architecture_Invariants.md) §27.
>
> **Accepted and implemented evaluation path:** `DecisionEvaluationService` and `DecisionEvidenceRunner`
> are exposed through the app's internal-only admin resource. The default-off switch gates real provider
> calls; `/evidence/run` reads a shipped, versioned corpus and returns payload-free M1–M5 metrics. The
> path registers no `DecisionEngine` bean, and the parent decision-plane ADR remains Deferred. See
> [ADR-jev-decision-plane-evaluation-path](../docs/adr/architecture/ADR-jev-decision-plane-evaluation-path.md)
> and the [activation plan](../docs/research/Jev-Decision-Plane-Activation-Plan-2026-10-07.md).

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.decision.shadow.intent.enabled` | `AMSHA_DECISION_SHADOW_INTENT_ENABLED` | `false` | Enables shadow intent classification using `DecisionEngine`. |
| `amsha.decision.shadow.tool-risk.enabled` | `AMSHA_DECISION_SHADOW_TOOL_RISK_ENABLED` | `false` | Enables shadow tool risk evaluation using `DecisionEngine`. |
| `amsha.decision.shadow.answer-quality.enabled` | `AMSHA_DECISION_SHADOW_ANSWER_QUALITY_ENABLED` | `false` | Enables shadow answer quality evaluation using `DecisionEngine`. |
| `amsha.decision.evaluation.enabled` | `AMSHA_DECISION_EVALUATION_ENABLED` | `false` | Opt-in evaluation path: when `true`, an internal-only operator may run one registered decision against a configured provider for evidence gathering. Off by default; it never registers a `DecisionEngine` bean, so it does not activate the decision plane (§27). |
| `amsha.decision.drift.window` | `AMSHA_DECISION_DRIFT_WINDOW` | `64` | Baseline and current observations per lane for drift and latency monitoring; must be at least `2`. |
| `amsha.decision.drift.confidence-threshold` | `AMSHA_DECISION_DRIFT_CONFIDENCE_THRESHOLD` | `0.15` | Absolute mean-confidence drift threshold; must be finite and in `[0,1]`. |
| `amsha.decision.drift.abstention-threshold` | `AMSHA_DECISION_DRIFT_ABSTENTION_THRESHOLD` | `0.20` | Absolute abstention-rate drift threshold; must be finite and in `[0,1]`. |
| `amsha.decision.cost.window` | `AMSHA_DECISION_COST_WINDOW` | `1000` | Priced decisions in the rolling cost-per-1,000 window; must be at least `1`. |
| `amsha.routing.intent-decision-router.enabled` | `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ENABLED` | `false` | Enables low-risk typed intent decision pilot router (Issue 07). |
| `amsha.routing.intent-decision-router.review-band.low` | `AMSHA_ROUTING_INTENT_DECISION_ROUTER_REVIEW_BAND_LOW` | `0.50` | Lower threshold for intent review band (below which judgment falls back to deterministic default). |
| `amsha.routing.intent-decision-router.review-band.high` | `AMSHA_ROUTING_INTENT_DECISION_ROUTER_REVIEW_BAND_HIGH` | `0.85` | Upper threshold for intent review band (at or above which direct routing applies). |
| `amsha.routing.intent-decision-router.timeout-ms` | `AMSHA_ROUTING_INTENT_DECISION_ROUTER_TIMEOUT_MS` | `500` | Per-evaluation deadline in milliseconds for the intent decision router. |
| `amsha.routing.intent-decision-router.activation-gate.candidate-evidence-approved` | `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ACTIVATION_GATE_CANDIDATE_EVIDENCE_APPROVED` | `false` | Activation gate: whether candidate model calibration on live traffic is formally approved. |
| `amsha.routing.intent-decision-router.activation-gate.cost-ceiling-approved` | `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ACTIVATION_GATE_COST_CEILING_APPROVED` | `false` | Activation gate: whether target cost ceiling and latency budget are formally approved. |
| `amsha.routing.intent-decision-router.activation-gate.rollout-owner` | `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ACTIVATION_GATE_ROLLOUT_OWNER` | `""` | Activation gate: designated accountable rollout and rollback owner for the pilot. **No closed set of values** — the only rule enforced is non-blank (`isActivationGateApproved()`), so a placeholder silently satisfies the gate; leave blank when no owner is assigned. A placeholder is announced once at startup by `ActivationGateReadiness` (advisory `WARN`; it does not close the gate). |

## Decision Provider Selection (Jev / System One)

Configuration-driven selection of the provider that answers decision (`choice` / `score` / `noul`) requests,
mirroring the LLM provider convention (`AMSHA_LLM_PROVIDER` plus `amsha.llm.<provider>.model`). **Inert:** this
is the configuration surface only — nothing consumes it into a `DecisionEngine`, so a value here changes no
behaviour. Selecting a provider is not activating the plane. See
[Jev-Provider-Integration-Plan-2026](../docs/research/Jev-Provider-Integration-Plan-2026.md) §8.

**Two axes.** A provider id names the System One **endpoint** that receives `POST /v1/systemone` — exactly as an
LLM provider id names the endpoint that receives `/v1/chat/completions`. `model` names the checkpoint served
*there*, the same role `amsha.llm.<provider>.model` plays. An id may therefore be a specific decision server
(`laya`, `kev`, `clm`), a generic local runtime host serving whichever decision model is loaded into it
(`omlx`), or a hosted API (`typesafe`) — `omlx` is a *runtime*, `laya` is a *model* it can serve.

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.decision.providers.selected` | `AMSHA_DECISION_PROVIDER` | `""` | Provider id to use; must match a declared slot. Blank keeps the plane inert. |
| `amsha.decision.providers.slots.<id>.enabled` | `AMSHA_DECISION_<ID>_ENABLED` | `false` | Whether the slot may be selected. |
| `amsha.decision.providers.slots.<id>.endpoint` | `AMSHA_DECISION_<ID>_ENDPOINT` | *(per slot)* | The `POST /v1/systemone` endpoint. The governance lane is derived from its host. |
| `amsha.decision.providers.slots.<id>.model` | `AMSHA_DECISION_<ID>_MODEL` | *(per slot)* | The decision model to request. **Required for a remote slot; optional for a self-hosted one**, where blank means "the server picks its own checkpoint" and the field is omitted from the request. |
| `amsha.decision.providers.slots.<id>.api-key-ref` | `AMSHA_DECISION_<ID>_API_KEY_REF` | `""` | A `SecretVault` **reference**, never a literal key. |
| `amsha.decision.providers.slots.<id>.timeout` | `AMSHA_DECISION_<ID>_TIMEOUT` | `PT5S` | Request execution timeout. |
| `amsha.decision.providers.slots.<id>.wire-profile` | `AMSHA_DECISION_<ID>_WIRE_PROFILE` | `JSON_OBJECT` | How `state` is rendered. Belongs to the **model**: a CLM model requires `PROSE`. |

Declared slots: `typesafe` (`jev-latest`, remote), `clm` (`clm-latest`, `PROSE`), `laya` (`laya-multilingual`),
`kev` (blank model — a self-hosted server picks its own), `omlx` (blank model — a local runtime that must serve
`/v1/systemone`, which omlx/LM Studio do not).

**Fail-closed resolution.** `DecisionProviderSelector` derives the lane from the endpoint host — the literal
`127.0.0.1` selects the self-hosted lane, anything else the remote lane — and refuses `localhost`, `::1`, and
other `127.x` hosts as **ambiguous**, because the two lanes carry different governance. It also refuses a
**remote** slot with no `model` (a self-hosted slot may omit it), a credentialed remote provider (`typesafe`)
with no `api-key-ref`, a non-positive `timeout`, and a CLM model declared without `WIRE_PROFILE=PROSE`.
Refusals name the operator configuration at fault and never a credential.

**Not wired yet.** The selector carries `api-key-ref` as a `SecretVault` reference and never resolves it — but
nothing else resolves it either. The remote lane's governed transport has a vault; the self-hosted lane uses a
plain HTTP client and sends its own `X-Amsha-Loopback-Token` header instead. So a declared `api-key-ref` is
intent, not a working credential, and a secured Laya/CLM endpoint that expects `Authorization: Bearer` needs an
auth mapping that does not exist yet.

## Multimodal Message Mapping & Capability Resolution

When channel messages contain media attachments (`MediaAttachment`), `MultimodalMessageMapper` converts validated attachments into LangChain4j `Content` items:
- `IMAGE` → `ImageContent.from(Image)`
- `AUDIO` → `AudioContent.from(Audio)`
- `DOCUMENT_PDF` → `PdfFileContent.from(PdfFile)`

OMLX's recognized local Qwen vision models are image-only. Audio transcription requires an audio-capable model such as Gemini: use `AMSHA_LLM_PROVIDER=gemini` with `AMSHA_GEMINI_API_KEY`, or set `AMSHA_INFINITE_LOOP_LLM_PROVIDERS=gemini` when using the infinite-loop provider. A composite containing OMLX safely intersects away `AUDIO`.

If the active model (or candidate model in failover routing) does not support a modality, or if an attachment exceeds `maxFileSize`, `maxMediaPerRequest`, or the configured cumulative turn budget (50 MiB by default), `MultimodalMessageMapper` automatically falls back to clean, structured text placeholders:
- `[Attached Document: <filename> - Extracted Text:\n<text>]` (for PDF documents when native PDF is unsupported)
- `[Attached Media: <filename> (<mime>, <size>) - Model <name> does not support <type> modality]`
- `[Attached Media: <filename> (<mime>, <size>) - Exceeds model maximum supported size of <formatted_limit>]` (e.g. `10.0 MB`)
- `[Attached Media: <filename> (<mime>, <size>) - Cumulative turn media payload exceeded limit of 50.0 MB]`
- `[Attached Media: <filename> (<mime>) - Exceeds maximum batch media limit of <limit> items]`
- `[Attached Media: <filename> (<mime>) - Extraction and formatting could not be completed]`

Routing wrappers (`MultiModelChatModel`, `OrderedFailoverChatModel`) compute the safe capability intersection across candidate models at composition time, preventing runtime schema or modality mismatch errors.

## Multimodal Telemetry & Metrics

The reasoning pipeline emits payload-free Micrometer metrics under `amsha.media.*` using strictly bounded categorical tag values (`source`, `type`, `status`, `reason`). No filenames, user IDs, URLs, payloads, credentials, or prompts are ever included in metric tags:

| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `amsha.media.ingestion.total` | Counter | `type`, `source`, `status` | Tracks media attachment ingestion attempts (`status=attached` or `status=fallback`). |
| `amsha.media.fallback.total` | Counter | `reason`, `type` | Granular breakdown of fallback reasons when attachments degrade to structured text. |
| `amsha.media.extraction.duration` | Timer | `type`, `status` | Execution duration for fallback text extraction (e.g. PDF text extraction). |
| `amsha.media.extraction.chars` | Counter | `type` | Count of characters extracted during successful text fallback extractions. |

> **Dashboard Note on Volume vs Reason**: `amsha.media.ingestion.total{status="fallback"}` tracks the aggregate count of attachments routed to fallback degradation, while `amsha.media.fallback.total{reason="..."}` provides the categorical cause breakdown. To avoid double-counting total media volume, query `amsha.media.ingestion.total` for volume metrics.

## Profile Routing & Modality Route Seeder (P7)

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.profile-routing.enabled` | `AMSHA_LLM_PROFILE_ROUTING_ENABLED` | `false` | When `true`, O3 profile-scoped routing resolves `USER`/`WORKSPACE`/`SYSTEM` model profiles from `LlmProfileStorePort` before falling back to deployment config. |
| `amsha.llm.modality-route-seeder.enabled` | `AMSHA_LLM_MODALITY_ROUTE_SEEDER_ENABLED` | `false` | When `true`, P7 test-only seeder converges the persisted routing configuration on one shared OMLX connection (`omlx-modality-shared`) and **two** modality profiles (`text→Ornith-1.5-35B-A3B-MLX-4bit`, `image→Qwen3.8-27B-4bit`) sharing that connection, plus a single `SYSTEM` `DEFAULT` route assignment `orderedProfileIds=[text, image]` (`modality-route-system`). Requires `amsha.llm.profile-routing.enabled=true` and vault `llm.api.key`; both are evaluated **before** any mutation. **Install before remove**: the canonical route is created or repaired first, and only once it is confirmed in place are colliding routes and retired profiles disabled — so a failure in between leaves the pre-existing configuration serving traffic untouched. **Non-destructive**: nothing is ever deleted; colliding `SYSTEM DEFAULT` routes (including a legacy `sys-legacy-route`) are *disabled* in place, retaining the row so legacy routing can be restored by hand. Every write is a compare-and-set through the port's revision-checked update, so a concurrent operator edit wins and the next startup reconverges. Convergent, not insert-only — a non-`ACTIVE` connection is activated through the port, and a disabled or drifted profile/route is repaired. Remains inert when `false` (production default). Keep `false` in production; enable only for local P7 verification. |

> **Convergence counts *enabled* routes, not rows.** Disabling rather than deleting means a machine
> migrated from legacy routing legitimately keeps a disabled `sys-legacy-route` row alongside the
> canonical `modality-route-system`. The invariant asserted by
> `[MODALITY-SEEDER] ACTIVE SYSTEM DEFAULT route` is therefore "exactly one **enabled**
> `SYSTEM DEFAULT` route", not "exactly one row". A startup that cannot install the canonical
> route logs `[MODALITY-SEEDER] Canonical route ... could not be installed` and disables nothing.

### No AUDIO chat profile (native audio-chat is BLOCKED)

The seeder deliberately installs **no** `AUDIO` multimodal chat profile. `NemotronLabs-VoiceChat-11B-8bit`
was seeded by an earlier revision, but OMLX cannot serve it: the model's `config.json` declares
`"model_type": "nemotron_voicechat"` and OMLX's bundled `mlx_lm` ships no module for that
architecture, so every request fails with `409 Model type nemotron_voicechat not supported`.
A profile that is selectable but permanently broken is worse than no profile, so:

* no `AUDIO` profile is created, and `modality-audio-nemotron` is **not** in `orderedProfileIds`;
* a `modality-audio-nemotron` row left over from an earlier run is disabled on startup
  (`[MODALITY-SEEDER] Disabled deprecated profile ...`), never deleted;
* an audio attachment therefore matches no profile and degrades to the structured text
  placeholder. When enabled, the governed STT path transcribes unsupported audio to bounded
  text and routes it to the text profile — see
  `.scratch/lc4j-capability-adoption/issues/11-p7-audio-transcription-port.md` (local tracker artifact).

Audio input is an execution/port concern, not a model-profile concern: Whisper is an STT
execution port and is **never** registered as an `AUDIO` chat profile. Text-to-speech
(`Qwen3-TTS-12Hz-1.7B-Base-bf16`) is a separate future output capability and is out of scope for
the audio-input routing matrix.

## Speech Output Policy (P8 / Issue 12)

When `amsha.tts.enabled=true` and `SpeechSynthesisPort` is resolvable, `SpeechReplyService`
synthesizes an optional audio reply for *finished* agent turns only. Approval prompts,
intermediate statuses, and failures are never synthesized — they are delivered as text.
Synthesis and channel delivery run off the Vert.x event loop; an event-loop call fails
closed to text rather than blocking.

`SpeechOutputPolicy.resolve(...)` decides the `OutputMode` for one turn, highest precedence first:

1. **Explicit directive** in the user's request text — positive ("reply with audio",
   "audio reply", "read aloud") or negative ("text only", "don't send audio"). An incidental
   mention of "audio" (e.g. "how do audio codecs work?") stays `TEXT_ONLY`.
2. **`amsha.tts.default-output-mode`** (below), applied only to inbound-audio turns.
3. **`TEXT_ONLY`** safe fallback.

| Property | Environment variable | Default | Description |
|---|---|---|---|
| `amsha.tts.default-output-mode` | `AMSHA_TTS_DEFAULT_OUTPUT_MODE` | `TEXT_ONLY` | Mode for inbound-audio turns with no explicit directive: `TEXT_ONLY`, `TEXT_AND_AUDIO`, `AUDIO_ONLY`. |
| `amsha.tts.max-input-chars` | `AMSHA_TTS_MAX_INPUT_CHARS` | `1200` | Max speakable chars projected before synthesis; longer answers are truncated at a sentence boundary. |

`TEXT_AND_AUDIO` sends the text first, then the audio as a separate message. `AUDIO_ONLY`
sends audio and falls back to text if synthesis or delivery fails. The channel `sendAudio`
contract returns `false` (never throws) so the caller always keeps a text fallback.

On every startup with the seeder enabled it logs the post-condition on `[MODALITY-SEEDER]`:

```text
[MODALITY-SEEDER] ACTIVE SYSTEM DEFAULT route id=modality-route-system scope=SYSTEM/SYSTEM targetKind=DEFAULT enabled=true orderedProfileIds=[modality-text-ornith, modality-image-qwen] connection=omlx-modality-shared routeIds=[profile:modality-text-ornith, profile:modality-image-qwen] models=[text=Ornith-1.5-35B-A3B-MLX-4bit, image=Qwen3.8-27B-4bit] audioProfile=none (native audio-chat BLOCKED on OMLX)
```

`[MODALITY-SEEDER] SYSTEM DEFAULT route invariant violated:` instead means the configuration did not converge and routing fell back to the legacy path. Per-turn selection is logged by `LangChain4jLlmAdapter` at `DEBUG` under `com.amsha` as `[LLM-ADAPTER] Slab B route selected for role=...: profile:<profileId>`.

## Execution Trace Persistence & Retention (Issue 08)

Durable execution trace persistence operates outside the JTA transaction boundary via an asynchronous
bounded-queue worker (`TracePersistenceEngine`) running on a dedicated virtual thread. Trace records are
redacted and serialized before entering the queue.

Expired trace records are pruned periodically by `TraceRetentionReaper` via Quarkus `@Scheduled`.

| Property | Environment variable | Default | Description |
|---|---|---|---|
| `amsha.trace.persistence.queue-capacity` | `AMSHA_TRACE_PERSISTENCE_QUEUE_CAPACITY` | `10000` | Maximum capacity of the in-memory bounded queue before oldest events are dropped. |
| `amsha.trace.retention.max-age-days` | `AMSHA_TRACE_RETENTION_MAX_AGE_DAYS` | `7` | Maximum age in days to retain durable execution traces before scheduled pruning. |
| `amsha.trace.retention.max-runs-per-session` | `AMSHA_TRACE_RETENTION_MAX_RUNS_PER_SESSION` | `50` | Maximum number of runs retained per session before oldest runs are pruned. |
| `amsha.trace.retention.interval` | `AMSHA_TRACE_RETENTION_INTERVAL` | `24h` | Interval between scheduled trace retention pruning sweeps. |

## Testing

Run core tests:

```bash
./mvnw test -pl core
```
