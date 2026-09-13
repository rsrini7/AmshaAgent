# ADR-Gemini-Provider: Google Gemini AI Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `gemini`
- **Default Model**: `gemini-2.5-flash-lite`
- **Default Base URL**: `https://generativelanguage.googleapis.com/v1beta`
- **Credential Type**: `API_KEY` (`AMSHA_GEMINI_API_KEY`)

---

## 1. Context

Google Gemini models provide large context windows, multimodal comprehension, and native tool-calling capabilities.

---

## 2. Decision

1. **Adapter**: `quarkus-langchain4j-ai-gemini` / `GeminiAiChatModel` integrating Google AI Studio and Vertex AI APIs.
2. **Factories**: `GeminiProviderFactory` and `GeminiLlmProviderFactory`.
3. **Slots**: Long-context reasoning, multi-turn tool calling, classification, and failover in `infinite-loop`.

---

## 3. Consequences

- Native support for large context window tasks and Google AI reasoning models.
