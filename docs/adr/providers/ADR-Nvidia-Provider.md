# ADR-Nvidia-Provider: NVIDIA NIM Cloud Inference Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `nvidia`
- **Default Model**: `bytedance/seed-oss-36b-instruct`
- **Default Base URL**: `https://integrate.api.nvidia.com/v1`
- **Credential Type**: `API_KEY` (`AMSHA_NVIDIA_API_KEY`)

---

## 1. Context

NVIDIA NIM provides optimized microservices and hosted endpoints for enterprise-grade LLM execution.

---

## 2. Decision

1. **Adapter**: `OpenAiChatModel` targeting `https://integrate.api.nvidia.com/v1`.
2. **Factories**: `NvidiaProviderFactory` and `NvidiaLlmProviderFactory`.
3. **Slots**: Full support across agent reasoning, routing, classification, and `infinite-loop`.

---

## 3. Consequences

- Reliable enterprise inference on NVIDIA accelerated cloud infrastructure.
