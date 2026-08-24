# ADR-LlamaCpp-Provider: llama.cpp Server Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `llamacpp`
- **Default Model**: `qwen3`
- **Default Base URL**: `http://127.0.0.1:9000/v1`
- **Credential Type**: `NONE` (Keyless local provider)

---

## 1. Context

`llama.cpp` (`llama-server`) provides standalone C++ GGUF inference on CPU, Metal, CUDA, and Vulkan.

---

## 2. Decision

1. **Adapter**: `OpenAiChatModel` targeting local `llama-server` `/v1` endpoints.
2. **Factories**: `LlamaCppProviderFactory` and `LlamaCppLlmProviderFactory`.
3. **Slots**: Low-cost local fallback in `infinite-loop`, routing, and agent reasoning.

---

## 3. Consequences

- Universal C++ inference engine support across all operating systems.
