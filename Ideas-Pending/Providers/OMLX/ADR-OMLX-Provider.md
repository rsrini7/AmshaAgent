# ADR-OMLX-Provider: Apple Silicon MLX Server Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `omlx`
- **Default Model**: `mlx-community/Qwen3.6-35B-A3B-4bit`
- **Default Base URL**: `http://127.0.0.1:8000/v1`
- **Credential Type**: `NONE` (Keyless local provider)

---

## 1. Context

Apple MLX enables native Unified Memory architecture inference on Apple Silicon Macs (M1/M2/M3/M4) via `omlx` or `mlx_lm.server`.

---

## 2. Decision

1. **Adapter**: Uses `OpenAiChatModel` targeting local MLX OpenAI-compatible endpoints.
2. **Factories**: `OmlxProviderFactory` and `OmlxLlmProviderFactory`.
3. **Keyless Execution**: Supports unauthenticated local loopback connections.
4. **Slots**: High-speed local reasoning and compaction on macOS.

---

## 3. Consequences

- Maximum hardware acceleration on Apple Silicon hardware with zero quantization memory penalties.
