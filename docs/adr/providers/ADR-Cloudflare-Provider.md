# ADR: First-Class Cloudflare Workers AI Provider Integration

- **Status**: Accepted
- Date: 2026-08-16
- Decision owners: Amsha Agent maintainers
- Related: [Configuration](../../../docs/CONFIGURATION.md), [Architecture Invariants](../../../docs/Architecture_Invariants.md), [Extending Amsha](../../../docs/AMSHA_EXTENSION.md)

## Context

Amsha Agent supports multi-provider LLM routing with per-persona model selection and resilient failover. Cloudflare Workers AI provides serverless frontier and open-weights models (e.g. `@cf/meta/llama-3.3-70b-instruct-fp8-fast`, `@cf/meta/llama-3.1-8b-instruct`, `@cf/deepseek-ai/deepseek-r1-distill-qwen-32b`) on global edge infrastructure with OpenAI-compatible chat endpoints.

## Upstream Evidence

Authoritative upstream API evidence retrieved from Cloudflare documentation and verified via HTTP:

- **Cloudflare Base URL**: `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1`
- **Endpoint**: `/chat/completions` accepts standard OpenAI wire format with `Authorization: Bearer <token>` authentication.
- **Models**: High-performance models including `@cf/meta/llama-3.3-70b-instruct-fp8-fast`, `@cf/deepseek-ai/deepseek-r1-distill-qwen-32b`, and `@cf/mistral/mistral-7b-instruct-v0.2`.
- **Scope Boundary**: Cloudflare Workers AI supports OpenAI-compatible `/chat/completions` routes with token/account authentication.

## Decision

1. **First-Class Provider**: Register `cloudflare` as a first-class Amsha LLM provider (`ModelDefaults.PROVIDER_CLOUDFLARE = "cloudflare"`).
2. **Transport Reuse**: Reuse the existing LangChain4j OpenAI-compatible transport (`OpenAiChatModel` via `ChatModelFactory`) with Bearer token authentication and `applyJsonResponseFormat`.
3. **Defaults**:
   - Provider ID: `cloudflare`
   - Default Base URL: `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1`
   - Default Model: `@cf/meta/llama-3.3-70b-instruct-fp8-fast`
   - Authentication: `Authorization: Bearer <token>`
4. **URL Inference**: Automatically infer `cloudflare` from `cloudflare.com` base URLs.
5. **Slot Support**: Fully support `cloudflare` across LLM chat reasoning, intent classification (`InfiniteLoopIntentChatModelProvider`), compaction (`InfiniteLoopCompactionChatModelProvider`), and onboarding setup.

## Consequences

- Provider routing, agent reasoning, intent classification, compaction, and profile credential resolution are integrated via the standard OpenAI-compatible transport SPIs.
- Synthetic loopback validation confirms standard OpenAI chat completion wire serialization, Bearer authentication, tool-calling round-trips, and finish reason decoding.
- Reusing the existing in-tree LangChain4j OpenAI transport introduces no new external runtime dependencies.
