# ADR-OpenRouter-Provider: OpenRouter Multi-Model Gateway Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `openrouter`
- **Default Model**: `deepseek/deepseek-chat` (or `openai/gpt-oss-20b:free`)
- **Default Base URL**: `https://openrouter.ai/api/v1`
- **Credential Type**: `API_KEY` (`AMSHA_OPENROUTER_API_KEY`)

---

## 1. Context

OpenRouter aggregates 100+ AI models across dozens of hosting providers with unified OpenAI-compatible routing and automated failover.

---

## 2. Decision

1. **Adapter**: Configured via `OpenAiChatModel` pointing to `https://openrouter.ai/api/v1` with custom attribution headers (`HTTP-Referer`, `X-Title`).
2. **Factories**: `OpenRouterProviderFactory` and `OpenRouterLlmProviderFactory`.
3. **Roles**: Used across reasoning, routing, classification, and multi-key rotation in `infinite-loop`.

---

## 3. Consequences

- Access to unified multi-provider catalog with model-switching flexibility.
