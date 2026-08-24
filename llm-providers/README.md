# amsha-llm-providers

Houses all 21 built-in concrete LLM provider factories, LangChain4j concrete SDKs (`quarkus-langchain4j-openai`, `quarkus-langchain4j-anthropic`, `quarkus-langchain4j-ollama`, `quarkus-langchain4j-ai-gemini`), custom HTTP/SSE response wrappers, token resolvers, and `ChatModelFactory`.

`amsha-llm-providers` implements the contracts defined in `amsha-llm-provider-api`. `amsha-core` consumes providers decoupled via `ChatModelCreationPort` and CDI `Instance<T>` multi-bean discovery.

## Supported Providers

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
| Kimchi | `kimchi` | `minimax-m3` | `https://llm.kimchi.dev/openai/v1` |
| Foundry Local | `foundry` | `qwen3-14b` | `http://127.0.0.1:8000/v1` (or dynamic daemon port) |
| Gemini | `gemini` | `gemini-3.1-flash-lite` | `https://generativelanguage.googleapis.com/v1beta` |
| OMLX | `omlx` | `mlx-community/Qwen3.6-35B-A3B-4bit` | `http://127.0.0.1:8000/v1` |
| Llama.cpp | `llamacpp` | `qwen3` | `http://127.0.0.1:9000/v1` |
| Generic OpenAI | `generic-openai` | `gpt-4o-mini` | *(Configured via `AMSHA_GENERIC_OPENAI_BASE_URL`)* |

## Architecture & CDI Discovery

Concrete provider factories are annotated with `@ApplicationScoped` and discovered dynamically at runtime:
- **Agent Reasoning**: Implements `AgentChatModelProvider` in package `com.amsha.llm.providers.reasoning`.
- **Routing & Subsystems**: Implements `RoutingChatModelProvider` in package `com.amsha.llm.providers.routing`.
- **Custom Responses Chat Models**: Custom streaming & thinking-tag wrappers in `com.amsha.llm.providers.wrapper`.
- **Token Resolvers & CLI Helpers**: Caching and fallback resolution in `com.amsha.llm.providers.helper`.
- **Native Reflection**: Registered in `LlmProvidersReflectionConfig`.

## Adding a New Provider

To add a new provider to `amsha-llm-providers`:
1. Add constant identifiers in `amsha-llm-provider-api` (`ModelDefaults.java`).
2. Implement `AgentChatModelProvider` in `llm-providers/src/main/java/com/amsha/llm/providers/reasoning/<Name>ProviderFactory.java`.
3. Implement `RoutingChatModelProvider` in `llm-providers/src/main/java/com/amsha/llm/providers/routing/<Name>LlmProviderFactory.java`.
4. Register the new factory classes in `LlmProvidersReflectionConfig.java`.
5. Add unit coverage in `AgentProviderFactoryCoverageTest` and `RoutingProviderFactoryCoverageTest`.
