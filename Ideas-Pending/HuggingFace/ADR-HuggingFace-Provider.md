# ADR: First-Class Hugging Face Provider Integration

- Status: Accepted
- Date: 2026-08-16
- Decision owners: Amsha Agent maintainers
- Related: [Configuration](../../docs/CONFIGURATION.md), [Architecture Invariants](../../docs/Architecture_Invariants.md), [Extending Amsha](../../docs/AMSHA_EXTENSION.md)

## Context

Amsha Agent supports multi-provider LLM routing with per-persona model selection and resilient failover. Hugging Face provides access to state-of-the-art open models (e.g. `meta-llama/Llama-3.3-70B-Instruct`, `Qwen/Qwen2.5-Coder-32B-Instruct`, `deepseek-ai/DeepSeek-R1-Distill-Qwen-32B`) via its unified Inference API / Router.

## Upstream Evidence

Authoritative upstream API evidence retrieved from Hugging Face documentation:

- **Hugging Face Router Base URL**: `https://router.huggingface.co/v1`
- **Endpoint**: `/chat/completions` accepts standard OpenAI wire format with `Authorization: Bearer <key>` authentication (where the key is a Hugging Face user access token starting with `hf_`).
- **Models**: Serverless and dedicated inference endpoints supporting chat models such as `meta-llama/Llama-3.3-70B-Instruct`.
- **Scope Boundary**: Hugging Face router provides OpenAI-compatible `/chat/completions` routes for chat reasoning. Specialized task endpoints (token classification, text-to-image) stay out of scope for this transport.

## Decision

1. **First-Class Provider**: Register `huggingface` as a first-class Amsha LLM provider (`ModelDefaults.PROVIDER_HUGGINGFACE = "huggingface"`).
2. **Transport Reuse**: Reuse the existing LangChain4j OpenAI-compatible transport (`OpenAiChatModel` via `ChatModelFactory`) with Bearer token authentication and `applyJsonResponseFormat`.
3. **Defaults**:
   - Provider ID: `huggingface`
   - Default Base URL: `https://router.huggingface.co/v1`
   - Default Model: `meta-llama/Llama-3.3-70B-Instruct`
   - Authentication: `Authorization: Bearer <key>`
4. **URL Inference**: Automatically infer `huggingface` from `huggingface.co` base URLs.
5. **Slot Support**: Fully support `huggingface` across LLM chat reasoning, intent classification (`InfiniteLoopIntentChatModelProvider`), compaction (`InfiniteLoopCompactionChatModelProvider`), and onboarding setup.

## Consequences

- Provider routing, agent reasoning, intent classification, compaction, and profile credential resolution are integrated via the standard OpenAI-compatible transport SPIs.
- Synthetic loopback validation confirms standard OpenAI chat completion wire serialization, Bearer authentication, tool-calling round-trips, and finish reason decoding.
- Reusing the existing in-tree LangChain4j OpenAI transport introduces no new external runtime dependencies.
