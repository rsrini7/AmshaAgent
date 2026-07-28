# In-Process Local ML Inference Guide (ONNX Runtime & DJL Tokenizers)

## 1. Overview

Amsha Agent has an **opt-in JVM implementation** of in-process local embeddings using [ONNX Runtime Java](https://onnxruntime.ai/) (`1.27.0`) and [Deep Java Library (DJL) Tokenizers](https://djl.ai/) (`0.33.0`). It uses verified local files and makes no model or tokenizer download during inference. GraalVM native-image compilation is supported by the build, but native loading and inference remain release gates rather than a supported deployment claim.

---

## 2. Implementation Status & Scope

The implementation follows [ADR-ONNX-In-Process-Local-ML-Inference.md](../Ideas-Pending/ADR-ONNX-In-Process-Local-ML-Inference.md):

| Rollout Phase | Status | Key Deliverables & Components |
|---|---|---|
| **Phase 0: Foundation & Tokenizer** | **Partially verified** | JVM runtime, tokenizer, manifest verification, health, metrics, and reflection registration exist. Golden-corpus, all-supported-platform, leak, and native-runtime gates remain open. |
| **Phase 1: Local Embeddings & RAG** | **In progress** | `OnnxEmbeddingProviderFactory`, `OnnxEmbeddingRunner`, `OnnxEmbeddingBundleSpec`, `OnnxSessionLeaseManager`, and `OnnxInferenceScheduler` execute the verified JVM path. Cross-backend vector-index fingerprint/reset parity is still required before general availability. |
| **Phase 2 & 2.5: Classifier SPI Gates** | *Pending* | Core `ClassifierResult` SPI, `PostPolicyToolRiskInterceptor`, shadow mode score distribution |
| **Phase 3: Bounded Behavioral Activation** | *Pending* | Admin kill switches (`/admin/local-ml/**`), per-classifier runtime feature toggles |

Current evidence (2026-07-28): the real MPNet JVM smoke test completed a 768-dimensional, finite, L2-normalized embedding through `OrtSession.run()`. The existing macOS arm64 native binary also started with ONNX Runtime `1.27.0` ready and registered the ONNX MPNet slot. The native diagnostic probe must be run after its introducing native build; it executes one fixed-text embedding and verifies the ONNX success metric without exposing text or vector data. The cross-platform native inference gate remains open.

---

## 3. Security & Zero-Network Mandates

To uphold Amsha's safety invariants, `amsha-local-ml-onnx` enforces strict isolation rules:

1. **Zero Outbound Network Access**:
   - `OnnxTokenizerFactory` sets JVM properties `ai.djl.offline=true` and `OPT_OUT_TRACKING=true` upon initialization.
   - Throws an `IOException` if `DJL_OFFLINE` environment variable is explicitly set to `false`.
2. **JNI Hijack Defense**:
   - `rejectNativeLibraryOverride()` checks `RUST_LIBRARY_PATH` and `RUST_FLAVOR` environment variables and system properties.
   - Rejects non-blank DJL overrides so the tokenizer uses its packaged CPU binding; the model and tokenizer data themselves still come only from the verified local bundle.
3. **Artifact Integrity Verification**:
   - `OnnxModelBundleVerifier` verifies SHA-256 digests and file byte counts for `model.onnx` and `tokenizer.json` against `manifest.json` before opening any ONNX session.
   - Restricts `bundleId` names to regex `[a-zA-Z0-9][a-zA-Z0-9._-]*` and blocks path traversal (`..`, absolute paths) to prevent directory escaping.

---

## 4. Verified Model Bundle Structure & Manifest Mapping

Local model bundles are stored in subdirectories under `AMSHA_ONNX_MODEL_ROOT` (`data/models/` by default). The directory is gitignored and the model is never embedded in the application artifact.

### Directory Layout
```text
data/models/all-mpnet-base-v2-e8c3b32/
├── manifest.json       # Immutable bundle contract & SHA-256 digests
├── model.onnx          # ONNX graph weights (435 MB)
└── tokenizer.json      # Hugging Face WordPiece/BPE tokenizer configuration
```

### Manifest Configuration (`manifest.json`)
```json
{
  "bundleId": "all-mpnet-base-v2-e8c3b32",
  "revision": "e8c3b32edf5434bc2275fc9bab85f82640a19130",
  "modelFile": "model.onnx",
  "modelSha256": "74187b16d9c946fea252e120cfd7a12c5779d8b8b86838a2e4c56573c47941bd",
  "modelBytes": 435826548,
  "tokenizerFile": "tokenizer.json",
  "tokenizerSha256": "b8be2c30ba5dd723a6d5ee26d013da103d5408d92ddcb23747622f9e48f1d842",
  "tokenizerBytes": 466021,
  "embedding": {
    "tokenizerRevision": "e8c3b32edf5434bc2275fc9bab85f82640a19130",
    "inputIdsName": "input_ids",
    "attentionMaskName": "attention_mask",
    "tokenTypeIdsName": null,
    "outputName": "last_hidden_state",
    "maxSequenceLength": 512,
    "vectorDimension": 768,
    "paddingTokenId": 1,
    "poolingStrategy": "MASKED_MEAN",
    "l2Normalize": true,
    "spaceId": "sentence-transformers/all-mpnet-base-v2",
    "spaceRevision": "e8c3b32edf5434bc2275fc9bab85f82640a19130"
  }
}
```

---

## 5. Configuration Reference

To activate local ONNX embedding inference in `dist/.env` or `.env`:

```env
# 1. Enable local ONNX runtime
AMSHA_ONNX_ENABLED=true

# 2. Select ONNX as active provider in RAG rotation loop
AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=onnx

# 3. Model bundle ID matching directory under data/models/
AMSHA_EMBEDDING_ONNX_MODEL=all-mpnet-base-v2-e8c3b32
AMSHA_EMBEDDING_ONNX_TIMEOUT_SECONDS=5

# 4. Embedding space identity matching manifest.json
AMSHA_EMBEDDING_ONNX_SPACE_ID=sentence-transformers/all-mpnet-base-v2
AMSHA_EMBEDDING_ONNX_SPACE_REVISION=e8c3b32edf5434bc2275fc9bab85f82640a19130

# 5. Existing vector indexes must use the same dimension and fingerprint.
#    Phase 1 persistence parity is still in progress; reset development data before
#    changing an active embedding space rather than mixing vectors.
AMSHA_EMBED_DIMENSIONS=768
```

Install the pinned development bundle before enabling the slot:

```bash
mise run models:install-mpnet
```

`onnx` is never added to the default provider list. An ONNX-only configuration has no remote embedding fallback; a configured fallback is eligible only when it advertises the same embedding-space descriptor.

---

## 6. Architecture & Concurrency Safeguards

1. **Session Lease Management (`OnnxSessionLeaseManager`)**:
   - Uses per-bundle monitor synchronization to serialize model loads while allowing multi-model parallel inference.
   - Reference counts active leases (`leaseCount`). Closing a session during active inference is physically impossible because `OrtSession.close()` executes only when `leaseCount == 0`.
2. **Bounded CPU Inference Scheduler (`OnnxInferenceScheduler`)**:
   - Executes CPU inference on a bounded worker pool (`AMSHA_ONNX_INFERENCE_THREADS=2`, capacity `64`).
   - Propagates `WorkspaceContext` via `WorkspaceExecutors.wrap()`.
   - Checks absolute request deadlines before queueing, during lease acquisition, and before task execution.
3. **Reactive Non-Blocking Protection**:
   - `OnnxEmbeddingProviderFactory.rejectEventLoopThread()` inspects Vert.x context (`context.isEventLoopContext()`).
   - Fails fast if invoked on a Quarkus event loop thread to prevent thread starvation.
4. **Native Memory Determinism**:
   - All `OnnxTensor` instances (`input_ids`, `attention_mask`, `token_type_ids`) and `OrtSession.Result` native handles are enclosed in try-with-resources blocks. C++ memory is freed immediately upon completion.

---

## 7. GraalVM Native Image Verification

`OnnxReflectionConfig` centrally registers the manifest and embedding-contract JSON types. It is necessary metadata, not proof that ORT or DJL JNI works in a native executable.

Build the SQLite native binary with GraalVM:

```bash
mise run build:native-local
```

Then launch it with an explicitly installed bundle and ONNX-only provider slot:

```bash
AMSHA_ONNX_ENABLED=true \
AMSHA_ONNX_MODEL_ROOT="$PWD/data/models" \
AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=onnx \
AMSHA_EMBEDDING_ONNX_MODEL=all-mpnet-base-v2-e8c3b32 \
AMSHA_EMBEDDING_ONNX_SPACE_ID=sentence-transformers/all-mpnet-base-v2 \
AMSHA_EMBEDDING_ONNX_SPACE_REVISION=e8c3b32edf5434bc2275fc9bab85f82640a19130 \
AMSHA_EMBED_DIMENSIONS=768 \
./dist/amsha-sqlite serve
```

From another terminal, run:

```bash
bash rest-http/scripts/run_all_validations.sh --onnx
```

This proves the native process starts, reports ONNX readiness, and sees the verified bundle. `ONNX-2` calls the internal-only fixed-text probe, requires an ONNX success-counter increment, and therefore proves native `OrtSession.run()` when it passes. A native inference smoke test on every supported target remains mandatory before Phase 0 is closed. Do not rely on undocumented ORT extraction locations or system-property library overrides; they are not an approved deployment interface.

The macOS arm64 build/start/readiness procedure above passed on 2026-07-28. It is evidence for that target only; Linux x86-64, Linux arm64, and a real native embedding execution remain required gates.

---

## 8. Frequently Asked Questions (FAQ)

### Q: How did you determine `AMSHA_EMBEDDING_ONNX_MODEL`, `SPACE_ID`, and `SPACE_REVISION`?
- **`AMSHA_EMBEDDING_ONNX_MODEL`**: The exact folder name under `data/models/` (`all-mpnet-base-v2-e8c3b32`), matching `"bundleId"` in `manifest.json`.
- **`AMSHA_EMBEDDING_ONNX_SPACE_ID`**: Read from `"spaceId"` inside `"embedding"` in `manifest.json` (`sentence-transformers/all-mpnet-base-v2`).
- **`AMSHA_EMBEDDING_ONNX_SPACE_REVISION`**: Read from `"spaceRevision"` in `manifest.json` (`e8c3b32edf5434bc2275fc9bab85f82640a19130`), which is the Hugging Face Git commit SHA.

### Q: Does a successful native build prove ONNX inference works in the native binary?
No. A native image can compile while a JNI resource, dynamic-library loader, reflection path, or tokenizer binding still fails at runtime. Follow the native build, launch, and REST readiness procedure in Section 7, then add a real native inference smoke test before considering the Phase 0 native gate closed.

### Q: How do I validate local ONNX inference over REST?
Run the REST validation script:
```bash
bash rest-http/scripts/run_all_validations.sh --onnx
```
This executes `ONNX-1` (health check `/q/health`), `ONNX-2` (an internal fixed-text inference probe plus ONNX success-metric increment), and `ONNX-3` (model bundle size/digest verification). The focused JVM inference proof is separate:

```bash
./mvnw -pl local-ml-onnx -Dtest=OnnxEmbeddingModelSmokeTest test
```
