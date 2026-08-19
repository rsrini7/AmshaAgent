# ADR-Ollama-Provider: Local Ollama Model Server Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `ollama`
- **Default Model**: `mistral-small:latest` (or `llama3.2:3b`, `qwen2.5-coder:7b`)
- **Default Base URL**: `http://localhost:11434`
- **Credential Type**: `NONE` (Keyless local provider)

---

## 1. Context

Ollama allows local developers to run GGUF models locally on CPU, GPU, and Apple Silicon with zero external API dependencies.

---

## 2. Decision

1. **Adapter**: Uses `quarkus-langchain4j-ollama` extension for native Ollama API and OpenAI-compatible `/v1` endpoints.
2. **Factories**: `OllamaProviderFactory` and `OllamaLlmProviderFactory`.
3. **Keyless Bypass**: Registered in `ProviderKeySpecs.LLM_KEYLESS_PROVIDERS` and `COMPACTION_CLASSIFIER_KEYLESS_PROVIDERS` to skip API key validation.
4. **Slots**: Offline LLM reasoning, local intent classification, and local context compaction.

---

## 3. Consequences

- 100% private, local model execution with zero cloud egress.
