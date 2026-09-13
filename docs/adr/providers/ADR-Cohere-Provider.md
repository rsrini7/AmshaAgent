# ADR-Cohere-Provider: Cohere LLM Provider Integration

- **Status**: Accepted

## Context
Amsha Agent supports multiple LLM providers (Ollama, Groq, Gemini, OpenAI, OpenRouter, OMLX, Nvidia, Cerebras, Sarvam, OpenCode, Hugging Face, Mistral, Cloudflare, LlamaCpp, ClaudeCode, InfiniteLoop).
Cohere provides high-performance enterprise and research models including `command-r-plus-08-2024`, `command-r-08-2024`, `command-r-plus`, and `command-r` with support for tool calling, structured JSON output, and multilingual reasoning.
Cohere exposes an OpenAI-compatible endpoint at `https://api.cohere.ai/compatibility/v1`.

## Decision
1. **Provider Identification**:
   - Provider Name: `cohere`
   - Default Base URL: `https://api.cohere.ai/compatibility/v1`
   - Default Model: `command-r-08-2024`
   - API Key Variable: `AMSHA_COHERE_API_KEY`
2. **Transport Reuse**: Reuse the existing LangChain4j OpenAI-compatible transport (`OpenAiChatModel` via `ChatModelFactory.createOpenAi`) with Bearer token authentication. `RoutingChatModelProvider` configures `applyJsonResponseFormat` for structured JSON output extraction.
3. **SPI Conformance**: Implement both `AgentChatModelProvider` (CDI factory for agent reasoning) and `RoutingChatModelProvider` (routing factory SPI for dynamic slot execution).
4. **Slot Inheritance**: Ensure `AMSHA_COMPACTION_COHERE_API_KEY` and `AMSHA_CLASSIFIER_COHERE_API_KEY` inherit from the unified `AMSHA_COHERE_API_KEY` when slot-specific keys are omitted (peer-consistent with Mistral and Cloudflare slots), while model and base URL use standard defaults.

## Consequences
- Cohere is available as a first-class provider (`AMSHA_LLM_PROVIDER=cohere`) and as a slot in `infinite-loop`.
- No new external SDK dependencies are introduced (OpenAI wire compatibility over HTTP).
