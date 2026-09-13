# ADR-Foundry-Local-Provider: Microsoft Foundry Local CLI & Daemon Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `foundry`
- **Default Model**: `qwen3-14b`
- **Default Base URL**: `http://127.0.0.1:8000/v1` (dynamically overridden by daemon port)
- **Credential Type**: `NONE` (Keyless local provider)

---

## 1. Context

Microsoft Foundry Local provides local execution of ONNX and GGUF models on Apple Silicon (Metal), Windows (DirectML), and Linux (CUDA/CPU) through the `foundry` CLI and `foundry server` background daemon.

When `foundry server` runs, it writes active HTTP endpoint configuration into `~/.foundry/daemon.json`. When the daemon is stopped, the `foundry complete` CLI command can run one-shot completions directly.

---

## 2. Decision

1. **Dual HTTP Daemon + CLI Subprocess Transport**:
   - `FoundryResponsesChatModel` prioritizes the HTTP SSE endpoint (`/v1/chat/completions`) for high-throughput streaming and tool invocation.
   - If the HTTP daemon is unreachable (`ConnectException`, `HttpTimeoutException`, 404/503), it automatically invokes `FoundryCliExecutor` to execute completions via `foundry complete <model> "<prompt>" -o json`.

2. **Daemon Auto-Discovery**:
   - `FoundryDiscoveryHelper` parses `~/.foundry/daemon.json` to extract `web_urls` and dynamically select the active port if different from `http://127.0.0.1:8000`.

3. **Thinking Tag Normalization & Truncation Recovery**:
   - Strips `<think>...</think>` tags from reasoning models (Qwen 3, DeepSeek R1).
   - If output is truncated mid-thought (missing `</think>`), recovers content after `<think>` rather than returning a 0-token blank response.
   - If the model emits its entire response within `<think>`, strips tags and preserves inner text to prevent empty `FINISH` errors.

4. **Multi-Turn Tool Execution**:
   - Both HTTP and CLI paths retain full assistant tool execution requests and tool execution results across multi-turn agent conversations.

5. **Slots Supported**:
   - Primary LLM: `AMSHA_LLM_PROVIDER=foundry` / `AMSHA_FOUNDRY_MODEL`
   - Intent Classifier: `amsha.classifier.foundry.*` / `AMSHA_CLASSIFIER_FOUNDRY_MODEL`
   - Context Compaction: `amsha.compaction.foundry.*` / `AMSHA_COMPACTION_FOUNDRY_MODEL`
   - `infinite-loop` local fallback slot.

---

## 3. Consequences

- Fully offline, keyless agent operation using local ONNX / GGUF models managed by Foundry.
- Automatic transition between background HTTP daemon and one-shot CLI execution.
