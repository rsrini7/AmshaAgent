# Evaluated Providers & Technology Closeout Reference

This document tracks providers, runtimes, and APIs evaluated for Amsha Agent that were **disqualified, closed, or deemed unsuitable for 100% free / no-card access**. It serves as a permanent reference to avoid redundant re-testing.

---

## 1. LLM Providers Evaluated & Disqualified

### A. GitHub Models (Azure AI / GitHub AI)
- **Evaluation Date**: 2026-08-17
- **Tested Endpoints**:
  - `https://models.inference.ai.azure.com/chat/completions` (Deprecated Azure AI gateway)
  - `https://models.github.ai/inference/chat/completions` (Official dedicated gateway)
- **Observed Response / Error**:
  ```json
  {
    "error": {
      "code": "github_models_retirement_brownout",
      "message": "GitHub Models is temporarily unavailable as part of a scheduled retirement brownout."
    }
  }
  ```
- **Disqualification Reason**: GitHub officially decommissioned and retired the standalone GitHub Models service on July 30, 2026. The API returns retirement brownout errors and is no longer available.
- **Active Alternative**: OpenRouter (`:free` models), Cloudflare Workers AI (10k free daily neurons), Groq, or Google Gemini.

---

### B. SambaNova Cloud
- **Evaluation Date**: 2026-08-16
- **Tested Endpoint**: `https://api.sambanova.ai/v1/chat/completions`
- **Tested Models**: `DeepSeek-R1-Distill-Llama-70B`, `Qwen2.5-72B-Instruct`, `Meta-Llama-3.3-70B-Instruct`
- **Observed Response / Error**:
  ```json
  {
    "error": {
      "balance_units": 0,
      "billing_portal_url": "https://cloud.sambanova.ai/plans/billing",
      "code": "PAYMENT_METHOD_REQUIRED",
      "message": "A payment method is required. Add one at https://cloud.sambanova.ai/plans/billing to continue."
    }
  }
  ```
- **Disqualification Reason**: SambaNova transitioned all API access behind a mandatory credit card / payment method requirement (`PAYMENT_METHOD_REQUIRED`). It fails the zero-billing / no-card requirement.
- **Active Alternative**: Groq (Llama 3.3 / Scout) and Cerebras (Qwen 3 235B / Llama 3.3).

---

### C. Direct Anthropic API (`api.anthropic.com`)
- **Evaluation Date**: 2026-08-10
- **Evaluated Endpoints**: `https://api.anthropic.com/v1/messages`
- **Disqualification Reason**: Anthropic does not provide a perpetual free monthly tier for direct API keys.
- **Active Alternative in Amsha**: Integrated via `claude` role/provider using local Claude Code CLI OAuth credentials (`~/.claude/credentials.json` or system vault).

---

## 2. In-Process Runtimes & Frameworks Evaluated & Closed

### A. GraalPy (In-Process Python Polyglot)
- **Closeout Date**: 2026-08-15 (Documented in [Sarvam-GraalPy-Experiment-Closeout.md](GraalPy/Sarvam-GraalPy-Experiment-Closeout.md))
- **Objective**: Embed Python AI SDKs (such as Sarvam AI or specialized tokenizers) in-process within Quarkus.
- **Disqualification Reasons**:
  1. **Runtime Footprint**: Added +162 MB of runtime JARs and platform-dependent native wheels (e.g. `pydantic-core`).
  2. **Concurrency Restrictions**: Incompatible with Java Virtual Threads (`@RunOnVirtualThread`); required bounded OS platform threads.
  3. **Filesystem Sandbox Breach**: Required `HostIO.READ` permissions to load native wheels, defeating strict in-process sandboxing.
- **Active Alternative**: Pure Java HTTP client bindings over standard OpenAI-compatible REST endpoints (Sarvam AI is natively integrated in `amsha-core` via `SarvamProviderFactory`).

---

### B. Deeplearning4j (DL4J)
- **Evaluation Date**: 2026-08-12 (Documented in [Deeplearning4j.md](Deeplearning4j.md))
- **Objective**: In-process neural network inference in Java.
- **Disqualification Reasons**:
  1. **Oversized Architecture**: Designed for distributed training (Spark/Hadoop) rather than lightweight inference.
  2. **Native Bloat**: Bundles heavyweight ND4J BLAS binaries with difficult GraalVM Native Image compilation characteristics.
- **Active Alternative**: Microsoft **ONNX Runtime Java** + DJL Tokenizers (already active in `amsha-local-ml-onnx` for in-process embeddings and intent classification).

---

## Summary Matrix

| Candidate | Category | Evaluated For | Disposition | Primary Reason |
|---|---|---|---|---|
| **GitHub Models** | LLM Provider | Free multi-model API | **Retired / Closed** | Service retired by GitHub (HTTP retirement brownouts). |
| **SambaNova** | LLM Provider | Free fast inference | **Disqualified** | Mandatory credit card required (`PAYMENT_METHOD_REQUIRED`). |
| **Anthropic Direct** | LLM Provider | Claude 3.5 Sonnet | **CLI-OAuth Only** | No perpetual free API key tier; OAuth CLI used instead. |
| **GraalPy** | Polyglot Engine | Python SDK bridge | **Rejected** | +162MB footprint, native wheel ABI issues, no Virtual Thread support. |
| **Deeplearning4j** | ML Framework | In-process inference | **Rejected** | Training bloat & native image incompatibility; ONNX chosen. |
