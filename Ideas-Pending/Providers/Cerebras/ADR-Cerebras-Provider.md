# ADR-Cerebras-Provider: Cerebras Wafer-Scale Inference Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `cerebras`
- **Default Model**: `qwen-3-235b-a22b-instruct-2507`
- **Default Base URL**: `https://api.cerebras.ai/v1`
- **Credential Type**: `API_KEY` (`AMSHA_CEREBRAS_API_KEY`)

---

## 1. Context

Cerebras delivers high-throughput inference powered by the Wafer-Scale Engine CS-3.

---

## 2. Decision

1. **Adapter**: `OpenAiChatModel` targeting `https://api.cerebras.ai/v1`.
2. **Factories**: `CerebrasProviderFactory` (`AgentChatModelProvider`) and `CerebrasLlmProviderFactory` (`RoutingChatModelProvider`).
3. **Slots**: Supported in agent reasoning, routing, classification, compaction, and `infinite-loop`.

---

## 3. Consequences

- Ultra-fast token generation for large open-weights models.
