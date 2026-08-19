# ADR: First-Class OpenCode Provider Integration

- Status: Accepted
- Date: 2026-08-16
- Decision owners: Amsha Agent maintainers
- Related: [Configuration](../../docs/CONFIGURATION.md), [Architecture Invariants](../../docs/Architecture_Invariants.md), [Extending Amsha](../../docs/AMSHA_EXTENSION.md)

## Context

Amsha Agent supports multi-provider LLM routing with per-persona model selection and resilient failover. OpenCode provides an AI gateway with support for both pay-per-use endpoints (**OpenCode Zen**) and subscription-based routing (**OpenCode Go**).

OpenCode models include both free-tier models (such as `deepseek-v4-flash-free` and `big-pickle`) and pay-per-use models (`deepseek-v4-flash`, `deepseek-v4-pro`).

## Upstream Evidence

Authoritative upstream API evidence retrieved from OpenCode documentation and live endpoint contracts:

- **OpenCode Zen Base URL**: `https://opencode.ai/zen/v1`
- **OpenCode Go Base URL**: `https://opencode.ai/zen/go/v1`
- **Endpoint**: `/chat/completions` accepts standard OpenAI wire format with `Authorization: Bearer <key>` authentication.
- **Models**: Includes `deepseek-v4-flash-free` (free tier default), `big-pickle` (stealth free coding model), `deepseek-v4-flash`, and `deepseek-v4-pro`.
- **Scope Boundary**: OpenCode provides OpenAI-compatible `/chat/completions` routes for chat reasoning. Claude/GPT specific custom shapes stay out of scope for this transport.

## Decision

1. **First-Class Provider**: Register `opencode` as a first-class Amsha LLM provider (`ModelDefaults.PROVIDER_OPENCODE = "opencode"`).
2. **Transport Reuse**: Reuse the existing LangChain4j OpenAI-compatible transport (`OpenAiChatModel` via `ChatModelFactory`) with Bearer token authentication and `applyJsonResponseFormat`.
3. **Defaults**:
   - Provider ID: `opencode`
   - Default Base URL: `https://opencode.ai/zen/v1`
   - Default Model: `deepseek-v4-flash-free` (free tier without credit consumption)
   - Authentication: `Authorization: Bearer <key>`
4. **URL Inference**: Automatically infer `opencode` from both `https://opencode.ai/zen/v1` and `https://opencode.ai/zen/go/v1` base URLs.
5. **Slot Support**: Fully support `opencode` across LLM chat reasoning, intent classification (`InfiniteLoopIntentChatModelProvider`), compaction (`InfiniteLoopCompactionChatModelProvider`), and onboarding setup.

## Consequences

- Provider routing, agent reasoning, intent classification, compaction, and profile credential resolution are integrated via the standard OpenAI-compatible transport SPIs.
- Synthetic loopback validation confirms standard OpenAI chat completion wire serialization, Bearer authentication, tool-calling round-trips, and finish reason decoding.
- Reusing the existing in-tree LangChain4j OpenAI transport introduces no new external runtime dependencies.
- OpenCode users can connect immediately using their OpenCode Zen API key without needing third-party proxy layers.
