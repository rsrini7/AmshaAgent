# ADR: First-Class Sarvam AI Provider Integration

- Status: Accepted
- Date: 2026-08-15
- Decision owners: Amsha Agent maintainers
- Related: [Configuration](../../../docs/CONFIGURATION.md), [Architecture Invariants](../../../docs/Architecture_Invariants.md), [Extending Amsha](../../../docs/AMSHA_EXTENSION.md)

## Context

Amsha Agent supports multi-provider LLM routing with per-persona selection and resilient failover. Sarvam AI offers Indic-language foundation models (e.g. `sarvam-105b`, `sarvam-105b-conversations`).

Prior exploratory evaluation (Phase 0) investigated polyglot Python integration (via GraalPy) and standalone Java HTTP clients. Phase 0 demonstrated that a custom Python/GraalPy runtime imposes significant native image complexity, footprint overhead, and host-filesystem access constraints without functional advantage for OpenAI-compatible chat endpoints.

## Upstream Evidence

Authoritative upstream API evidence retrieved from Sarvam AI OpenAPI specification:

- **Source**: `https://docs.sarvam.ai/openapi/apis.json`
- **Retrieved**: 2026-08-15
- **SHA-256**: `f54db1ebcbf1ab97961592f0c96fed9f20aaca2a0dfb5609240fbf70962e5e8d`
- **OpenAPI Server**: `https://api.sarvam.ai`
- **Endpoint**: `/v1/chat/completions` accepts standard `Authorization: Bearer <key>` authentication.
- **Models**: v1 models include `sarvam-105b` and `sarvam-105b-conversations`.
- **Scope Boundary**: `/v2/chat/completions` requires `api-subscription-key` and is out of scope. Translation endpoints and translation client abstractions are deferred and out of scope.

## Decision

1. **First-Class Provider**: Register `sarvam` as a first-class Amsha LLM provider (`ModelDefaults.PROVIDER_SARVAM = "sarvam"`).
2. **Transport Reuse**: Reuse the existing LangChain4j OpenAI-compatible transport (`OpenAiChatModel` via `ChatModelFactory`).
3. **Defaults**:
   - Provider ID: `sarvam`
   - Default Base URL: `https://api.sarvam.ai/v1`
   - Default Model: `sarvam-105b`
   - Authentication: `Authorization: Bearer <key>`
4. **No GraalPy/Python Dependencies**: Keep the GraalPy and Python SDK experiments closed. No polyglot dependencies or custom language bindings are added.
5. **No Unused Translation Client**: Translation is explicitly deferred. No speculative or unused translation clients are added.
6. **Selective Transport Promotion Trigger**:
   > Promote the SGP-J01 selective JDK transport only when a synthetic loopback regression reproduces a required active Amsha request or response contract that LangChain4j cannot encode or decode.

## Consequences

- Provider routing, agent reasoning, intent classification, compaction, and profile credential resolution are integrated via the standard OpenAI-compatible transport SPIs.
- Synthetic loopback validation confirms standard OpenAI chat completion wire serialization, Bearer authentication, tool-calling round-trips, and finish reason decoding.
- Reusing the existing in-tree LangChain4j OpenAI transport introduces no new external runtime dependencies.
- Clean separation between v1 chat completions (implemented) and specialized translation APIs (deferred).
