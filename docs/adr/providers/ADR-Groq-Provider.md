# ADR-Groq-Provider: Groq LPU Inference Engine Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `groq`
- **Provider Model Fallback**: `openai/gpt-oss-120b`
- **Default Base URL**: `https://api.groq.com/openai/v1`
- **Credential Type**: `API_KEY` (`AMSHA_GROQ_API_KEY`)

This ADR records Groq as a supported provider and its provider-specific model fallback; it does not select Groq globally. `AMSHA_LLM_PROVIDER` is unset by default and must be selected explicitly.

---

## 1. Context

Groq delivers ultra-low-latency inference (300–800 tokens/sec) via custom Language Processing Units (LPUs). In Amsha, Groq is heavily utilized for fast agent turn loops, real-time intent classification, and streaming chat.

---

## 2. Decision

1. **Adapter**: Uses `OpenAiChatModel` backed by `quarkus-langchain4j-openai` pointed to `https://api.groq.com/openai/v1`.
2. **Factories**: `GroqProviderFactory` (`AgentChatModelProvider`) and `GroqLlmProviderFactory` (`RoutingChatModelProvider`).
3. **Structured Output Workaround**: Set `PROMPT_DIRECTIVE` mode if Groq emits schema validation conflicts with native function calling.
4. **Slots**: Default primary provider in `infinite-loop`, default intent classifier, and context compaction engine.

---

## 3. Consequences

- Provides rapid agent iteration and minimal time-to-first-token.
