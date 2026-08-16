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
| Gemini | `gemini` | `gemini-2.5-flash-lite` | `https://generativelanguage.googleapis.com/v1beta` |
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

## Testing

Run core tests:

```bash
./mvnw test -pl core
```
