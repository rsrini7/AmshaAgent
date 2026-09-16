# ADR-OpenAI-Provider: OpenAI Official API Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `openai`
- **Default Model**: `gpt-4o-mini`
- **Default Base URL**: `https://api.openai.com/v1`
- **Credential Type**: `API_KEY` (`AMSHA_OPENAI_API_KEY`)

---

## 1. Context

OpenAI provides GPT and reasoning models with function calling, structured outputs, and embeddings.

---

## 2. Decision

1. **Adapter**: `quarkus-langchain4j-openai` / `OpenAiChatModel` targeting `https://api.openai.com/v1`.
2. **Factories**: `OpenAiProviderFactory` and `OpenAiLlmProviderFactory`.
3. **Slots**: Reasoning, tool calling, embeddings (`text-embedding-3-small`), and classification.

---

## 3. Consequences

- Standard baseline provider for tool calling and reasoning benchmarks.
