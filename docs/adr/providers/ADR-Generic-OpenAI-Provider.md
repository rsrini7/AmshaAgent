# ADR-Generic-OpenAI-Provider: Generic OpenAI-Compatible Endpoint Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `generic-openai`
- **Default Model**: `gpt-4o-mini`
- **Default Base URL**: `https://api.openai.com/v1` (or custom endpoint via configuration/vault)
- **Credential Type**: `API_KEY` (`AMSHA_GENERIC_OPENAI_API_KEY`)

---

## 1. Context

Allows connecting Amsha to any arbitrary third-party inference provider, reverse proxy (e.g. LiteLLM, vLLM, SGLang, Headroom proxy), or enterprise gateway that adheres to the standard OpenAI `/v1/chat/completions` specification.

---

## 2. Decision

1. **Adapter**: `OpenAiChatModel` parameterized with custom base URL and API key from environment / vault.
2. **Factories**: `GenericOpenAiProviderFactory` and `GenericOpenAiLlmProviderFactory`.
3. **Structured Output Fallback**: Supports both native JSON schema and prompt-directive structured outputs (`AMSHA_COMPACTION_STRUCTURED_OUTPUT_MODE`).

---

## 3. Consequences

- Maximum extensibility allowing connection to any custom LLM endpoint without code changes.
