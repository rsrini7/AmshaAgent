# ADR: First-Class Mistral AI Provider Integration

- **Status**: Accepted
- Date: 2026-08-16
- Decision owners: Amsha Agent maintainers
- Related: [Configuration](../../../docs/CONFIGURATION.md), [Architecture Invariants](../../../docs/Architecture_Invariants.md), [Extending Amsha](../../../docs/AMSHA_EXTENSION.md)

## Context

Amsha Agent supports multi-provider LLM routing with per-persona model selection and resilient failover. Mistral AI provides premier frontier open and proprietary models (e.g. `codestral-latest`, `mistral-small-latest`, `mistral-large-latest`, `pixtral-large-latest`) via La Plateforme API.

## Upstream Evidence

Authoritative upstream API evidence retrieved from Mistral AI documentation and verified via HTTP:

- **Mistral Base URL**: `https://api.mistral.ai/v1`
- **Endpoint**: `/chat/completions` accepts standard OpenAI wire format with `Authorization: Bearer <key>` authentication.
- **Models**: Frontier models supporting tool calling, JSON schema output, and high context windows (e.g. `codestral-latest`, `mistral-small-latest`).
- **Scope Boundary**: Mistral API provides OpenAI-compatible `/chat/completions` routes for chat reasoning. Embeddings and fine-tuning endpoints remain distinct.

## Decision

1. **First-Class Provider**: Register `mistral` as a first-class Amsha LLM provider (`ModelDefaults.PROVIDER_MISTRAL = "mistral"`).
2. **Transport Reuse**: Reuse the existing LangChain4j OpenAI-compatible transport (`OpenAiChatModel` via `ChatModelFactory`) with Bearer token authentication and `applyJsonResponseFormat`.
3. **Defaults**:
   - Provider ID: `mistral`
   - Default Base URL: `https://api.mistral.ai/v1`
   - Default Model: `codestral-latest`
   - Authentication: `Authorization: Bearer <key>`
4. **URL Inference**: Automatically infer `mistral` from `mistral.ai` base URLs.
5. **Slot Support**: Fully support `mistral` across LLM chat reasoning, intent classification (`InfiniteLoopIntentChatModelProvider`), compaction (`InfiniteLoopCompactionChatModelProvider`), and onboarding setup.

## Consequences

- Provider routing, agent reasoning, intent classification, compaction, and profile credential resolution are integrated via the standard OpenAI-compatible transport SPIs.
- Synthetic loopback validation confirms standard OpenAI chat completion wire serialization, Bearer authentication, tool-calling round-trips, and finish reason decoding.
- Reusing the existing in-tree LangChain4j OpenAI transport introduces no new external runtime dependencies.
