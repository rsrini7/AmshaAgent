# amsha-core

Core engine for Amsha Agent. Contains the agent loop, reasoning engines, model routing,
reflexes, safety guards, tool interfaces, and built-in LLM providers.

## LLM Providers

`amsha-core` hosts both the agent reasoning SPI (`AgentChatModelProvider`) and routing SPI
(`RoutingChatModelProvider`) for built-in LLM providers.

### Built-in Providers

| Provider | Provider ID | Default Model | Default Base URL |
|---|---|---|---|
| Groq | `groq` | `meta-llama/llama-4-scout-17b-16e-instruct` | `https://api.groq.com/openai/v1` |
| OpenAI | `openai` | `gpt-4o-mini` | `https://api.openai.com/v1` |
| Ollama | `ollama` | `mistral-small:latest` | `http://localhost:11434` |
| OpenRouter | `openrouter` | `openai/gpt-oss-20b:free` | `https://openrouter.ai/api/v1` |
| NVIDIA | `nvidia` | `bytedance/seed-oss-36b-instruct` | `https://integrate.api.nvidia.com/v1` |
| Cerebras | `cerebras` | `qwen-3-235b-a22b-instruct-2507` | `https://api.cerebras.ai/v1` |
| Sarvam AI | `sarvam` | `sarvam-105b` | `https://api.sarvam.ai/v1` |
| OpenCode | `opencode` | `deepseek-v4-flash-free` | `https://opencode.ai/zen/v1` |
| Hugging Face | `huggingface` | `meta-llama/Llama-3.3-70B-Instruct` | `https://router.huggingface.co/v1` |
| Mistral AI | `mistral` | `codestral-latest` | `https://api.mistral.ai/v1` |
| Cloudflare Workers AI | `cloudflare` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1` |
| Cohere | `cohere` | `command-r-plus-08-2024` | `https://api.cohere.ai/compatibility/v1` |
| Codex CLI | `codex` | `gpt-5.6-luna` | `https://chatgpt.com/backend-api/codex` |
| Claude Code | `claude-code` | `claude-sonnet-4-5` | `https://api.anthropic.com` |
| Antigravity CLI | `agy` | `gemini-3.1-flash-lite` | `https://generativelanguage.googleapis.com/v1beta/openai` |
| Gemini | `gemini` | `gemini-3.1-flash-lite` | `https://generativelanguage.googleapis.com/v1beta` |
| OMLX | `omlx` | `mlx-community/Qwen3.6-35B-A3B-4bit` | `http://127.0.0.1:8000/v1` |
| Llama.cpp | `llamacpp` | `qwen3` | `http://127.0.0.1:9000/v1` |
| Generic OpenAI | `generic-openai` | `gpt-4o-mini` | *(Configured via `AMSHA_GENERIC_OPENAI_BASE_URL`)* |

## Sarvam AI Configuration

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.sarvam.api-key` | `AMSHA_SARVAM_API_KEY` | — | Sarvam AI API key |
| `amsha.llm.sarvam.base-url` | `AMSHA_SARVAM_BASE_URL` | `https://api.sarvam.ai/v1` | Sarvam AI v1 API base URL |
| `amsha.llm.sarvam.model` | `AMSHA_SARVAM_MODEL` | `sarvam-105b` | Default chat model |
| `amsha.compaction.sarvam.api-key` | `AMSHA_COMPACTION_SARVAM_API_KEY` | — | Sarvam API key for compaction slot |
| `amsha.compaction.sarvam.model` | `AMSHA_COMPACTION_SARVAM_MODEL` | `sarvam-105b` | Compaction slot model |
| `amsha.compaction.sarvam.base-url` | `AMSHA_COMPACTION_SARVAM_BASE_URL` | `https://api.sarvam.ai/v1` | Compaction slot base URL |
| `amsha.classifier.sarvam.api-key` | `AMSHA_CLASSIFIER_SARVAM_API_KEY` | — | Sarvam API key for classifier slot |
| `amsha.classifier.sarvam.model` | `AMSHA_CLASSIFIER_SARVAM_MODEL` | `sarvam-105b` | Classifier slot model |
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
| `amsha.llm.cohere.model` | `AMSHA_COHERE_MODEL` | `command-r-plus-08-2024` | Default chat model |
| `amsha.compaction.cohere.api-key` | `AMSHA_COMPACTION_COHERE_API_KEY` | — | Cohere API key for compaction slot |
| `amsha.compaction.cohere.model` | `AMSHA_COMPACTION_COHERE_MODEL` | `command-r-plus-08-2024` | Compaction slot model |
| `amsha.compaction.cohere.base-url` | `AMSHA_COMPACTION_COHERE_BASE_URL` | `https://api.cohere.ai/compatibility/v1` | Compaction slot base URL |
| `amsha.classifier.cohere.api-key` | `AMSHA_CLASSIFIER_COHERE_API_KEY` | — | Cohere API key for classifier slot |
| `amsha.classifier.cohere.model` | `AMSHA_CLASSIFIER_COHERE_MODEL` | `command-r-plus-08-2024` | Classifier slot model |
| `amsha.classifier.cohere.base-url` | `AMSHA_CLASSIFIER_COHERE_BASE_URL` | `https://api.cohere.ai/compatibility/v1` | Classifier slot base URL |

## Codex Configuration

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.llm.codex.api-key` | `AMSHA_CODEX_API_KEY` | — | Optional direct API key / token (defaults to `~/.codex/auth.json` OAuth session) |
| `amsha.llm.codex.base-url` | `AMSHA_CODEX_BASE_URL` | `https://chatgpt.com/backend-api/codex` | Codex endpoint (defaults to direct ChatGPT Plus Responses endpoint; or `http://127.0.0.1:8787/v1` for hroom proxy, or `https://api.openai.com/v1`) |
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

## Testing

Run core tests:

```bash
./mvnw test -pl core
```
