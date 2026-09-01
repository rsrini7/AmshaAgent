# In-Process Local ML Inference Guide (ONNX Runtime & DJL Tokenizers)

## 1. Overview

Amsha Agent has an **opt-in JVM implementation** of in-process local embeddings using [ONNX Runtime Java](https://onnxruntime.ai/) (`1.27.0`) and [Deep Java Library (DJL) Tokenizers](https://djl.ai/) (`0.33.0`). It uses verified local files and makes no model or tokenizer download during inference. GraalVM native-image compilation is supported by the build, but native loading and inference remain release gates rather than a supported deployment claim.

---

## 2. Implementation Status & Scope

The implementation follows [ADR-ONNX-In-Process-Local-ML-Inference.md](../Ideas-Pending/ADR-ONNX-In-Process-Local-ML-Inference.md):

| Rollout Phase | Status | Key Deliverables & Components |
|---|---|---|
| **Phase 0: Foundation & Tokenizer** | **Release evidence open** | Python-reference equivalence and macOS arm64 native/lifecycle evidence pass. Linux x86-64, Linux arm64, and signed SBOM/provenance artifacts remain release gates. |
| **Phase 1: Local Embeddings & RAG** | **Implementation complete** | `OnnxEmbeddingProviderFactory`, `OnnxEmbeddingRunner`, `OnnxEmbeddingBundleSpec`, `OnnxSessionLeaseManager`, `OnnxInferenceScheduler`, 15-field `EmbeddingIndexDescriptor`, dual-dialect `V10` Flyway migrations (SQLite & PostgreSQL), filesystem descriptor manifest, non-destructive BM25/FTS fallback, and MicroProfile health integration (`VectorIndexCompatibilityHealthCheck`). |
| **Phase 2: Shadow Classifiers** | **Runtime/tooling complete; evidence open** | Disabled-by-default intent, tool-risk, and answer-quality adapters, verified bundle execution, payload-free shadow metrics, and reproducible preparation/export tooling are implemented. Human-approved datasets, calibrated models, error budgets, and retained shadow evidence remain pending. |
| **Phase 2.5: Core Classifier SPI Gate** | **Implementation complete** | Ordered post-policy tool-risk and answer-quality SPIs preserve deterministic decisions and HITL, permit only monotonic risk upgrades, share one pre-tool retry budget, and have no active ONNX implementation. |
| **Phase 3A: Active Classifiers & Controls** | **Implementation complete** | Internal-only, mandatory-audited per-classifier runtime state distinguishes requested/effective enablement, rejects missing implementations, and drains accepted leases after disable. Active ONNX classifier interceptors bridge to core Phase 2.5 SPIs and preserve deterministic execution when disabled. |
| **Phase 3B: Local Cross-Encoder Reranking** | **Implementation complete; evidence gated** | `LocalOnnxScoringAdapter` (`RerankerPort` via CDI `Instance` + `@Unremovable`), `OnnxScoringBundleSpec` (384-token `text-pair` contract, `BATCH_BY_ONE` logits), `OnnxScoringRunner` + `OnnxTextInputSupport` (shared truncation bounded to manifest `maxSequenceLength`), `RerankingService` deterministic fallback preserving `ScoredChunk.score()` and tagging `amsha.rag.rerank.fallback`, and non-destructive `searchReranked` path in `RagContextEnricher`. Fail-closed by default (`amsha.rag.reranker.enabled=false`); executable benchmark gate under `scripts/evaluate_reranker_benchmark.py` with 105-query graded corpus, paired permutation testing, and p95≤250 ms gate. |

Current evidence (2026-07-29): the pinned Python-reference corpus passes with minimum cosine `0.999999998` against the FP32 `0.9999` threshold, 100% for every declared partition, and stable nearest-neighbour rankings. The macOS arm64 record passes three fresh native lifecycles, 100 inference probes per lifecycle, graceful shutdown, and maximum RSS growth of `22,816 KiB` against the `64 MiB` limit. Multi-backend vector parity also passes. The release harness still fails closed when either Linux target or signed artifacts are missing.

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
  "estimatedResidentBytes": 872585138,
  "embedding": {
    "tokenizerRevision": "e8c3b32edf5434bc2275fc9bab85f82640a19130",
    "inputIdsName": "input_ids",
    "attentionMaskName": "attention_mask",
    "tokenTypeIdsName": null,
    "outputName": "last_hidden_state",
    "maxSequenceLength": 384,
    "chunkingStrategy": "FIRST_MAX_TOKENS",
    "chunkingRevision": "v1",
    "maxWindowTokens": 384,
    "windowOverlapTokens": 0,
    "multiWindowAggregation": "DIRECT",
    "vectorDimension": 768,
    "paddingTokenId": 1,
    "poolingStrategy": "MASKED_MEAN",
    "l2Normalize": true,
    "spaceId": "sentence-transformers/all-mpnet-base-v2",
    "spaceRevision": "e8c3b32edf5434bc2275fc9bab85f82640a19130"
  },
  "source": {
    "repository": "https://huggingface.co/sentence-transformers/all-mpnet-base-v2",
    "revision": "e8c3b32edf5434bc2275fc9bab85f82640a19130",
    "modelPath": "onnx/model.onnx",
    "tokenizerPath": "tokenizer.json",
    "license": "Apache-2.0"
  }
}
```

Phase 1 accepts only the manifest-declared `FIRST_MAX_TOKENS` `v1` contract with
`maxWindowTokens` equal to `maxSequenceLength`, zero overlap, and `DIRECT`
aggregation. These values flow through `EmbeddingSpaceDescriptor` into the durable
index fingerprint; unsupported strategies fail bundle validation instead of silently
reusing a compatible index.

`estimatedResidentBytes` is a conservative per-bundle admission estimate, not a
measurement of process RSS. Legacy manifests that omit it default to twice the verified
model-plus-tokenizer bytes. The session manager reserves the estimate before loading and
evicts only idle sessions when either configured admission budget is full.

The required `source` object written by the development installer is an unsigned,
descriptive declaration. Its URL, relative paths, and SPDX license are validated, but
it is not a trust root, signature, SBOM, or SLSA provenance statement. Release evidence
is verified separately as described in Section 8.

---

## 5. Configuration Reference

To activate local ONNX embedding inference in `dist/.env` or `.env`:

```env
# 1. Enable local ONNX runtime
AMSHA_ONNX_ENABLED=true
AMSHA_ONNX_MAX_LOADED_MODELS=2
AMSHA_ONNX_MAX_RESIDENT_MB=1024

# 2. Select ONNX as active provider in RAG rotation loop
AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=onnx

# 3. Model bundle ID matching directory under data/models/
AMSHA_EMBEDDING_ONNX_MODEL=all-mpnet-base-v2-e8c3b32
AMSHA_EMBEDDING_ONNX_TIMEOUT_SECONDS=5

# 4. Embedding space identity matching manifest.json
AMSHA_EMBEDDING_ONNX_SPACE_ID=sentence-transformers/all-mpnet-base-v2
AMSHA_EMBEDDING_ONNX_SPACE_REVISION=e8c3b32edf5434bc2275fc9bab85f82640a19130

# 5. Existing vector indexes must use the same dimension and fingerprint.
#    Reset development data before changing an active embedding space rather than
#    mixing vectors.
AMSHA_EMBED_DIMENSIONS=768
```

Install the pinned development embedding bundle before enabling the slot:

```bash
mise run models:install-mpnet
```

Install the pinned cross-encoder reranking bundle (used only when reranking is enabled):

```bash
mise run models:install-bge-reranker   # installs data/models/bge-reranker-v1 (Apache-2.0, Xenova/bge-reranker-base)
# or directly:
python3 .mise/tasks/models/install-bge-reranker.py
```

`onnx` is never added to the default provider list. An ONNX-only configuration has no
remote embedding fallback; a configured fallback is eligible only when it advertises
the same embedding-space descriptor. Existing incompatible vector namespaces still
retain the non-vector BM25/FTS keyword-search fallback.

---

## 5.1 Cross-Encoder Reranking Configuration

`LocalOnnxScoringAdapter` is the optional `RerankerPort` implementation. It is discovered via CDI `Instance<RerankerPort>` and retained for native builds with `@Unremovable`. When no reranker is available or `isAvailable()` is false, `RerankingService` falls back deterministically to the synthetic RRF order and increments `amsha.rag.rerank.fallback{reason=no_provider}`.

```env
# Enable reranking (requires ONNX runtime and a verified scoring bundle)
AMSHA_ONNX_ENABLED=true
AMSHA_RAG_RERANKER_ENABLED=true
AMSHA_RAG_RERANKER_BUNDLE_ID=bge-reranker-v1
AMSHA_RAG_RERANKER_TIMEOUT=PT5S
AMSHA_RAG_RERANKER_MAX_CANDIDATES=50
AMSHA_RAG_RERANKER_MAX_INPUT_BYTES=262144
```

Behavioral contract:

* **Score preservation** — `ScoredChunk.score()` (synthetic RRF fusion value) is retained byte-for-byte; `RerankedChunk.rerankScore` is used only for reordering.
* **Bounded execution** — scoring runs under `OnnxInferenceScheduler` with an absolute `Instant` deadline; `searchReranked` requests `contextChunks*3` candidates, reranks the top `maxCandidates`, and appends overflow with strictly lower synthetic scores.
* **Payload limits** — query and each candidate are rejected by UTF-8 byte length before tokenization; truncation is bounded to the manifest `maxSequenceLength` (384) and metered via `amsha.localml.onnx.truncation{component=cross_encoder}`.
* **Non-blocking guard** — `Context.isOnEventLoopThread()` fails fast; `@RunOnVirtualThread` callers are permitted.
* **Fallbacks are exhaustive** — `no_provider`, `descriptor_mismatch`, `execution_error`, `cardinality_mismatch`, `identity_mismatch`, `order_invalid`, `empty_result` all return the original RRF ordering and never invent chunks.

Scoring bundle manifest (`OnnxScoringBundleSpec`) pins the verified contract:

```json
"scoring": {
  "inputType": "text-pair",
  "inputIdsName": "input_ids",
  "attentionMaskName": "attention_mask",
  "tokenTypeIdsName": null,
  "outputName": "logits",
  "maxSequenceLength": 384,
  "paddingTokenId": 1,
  "outputLabel": "relevance",
  "scoreDirection": "HIGHER_IS_BETTER",
  "expectedOutputShape": "BATCH_BY_ONE"
}
```

Executable acceptance gate (105 graded queries, 20 candidates each, 2 long-candidate cases, 5 seeds, 10 000-permutation test, real ONNX inference when the bundle is present):

```bash
python3 scripts/evaluate_reranker_benchmark.py
python3 scripts/evaluate_reranker_benchmark.py --require-pass   # CI: exits non-zero when gate fails closed
```

Gates: `ΔnDCG@10 ≥ +0.02`, `ΔMRR@10 ≥ -0.005`, `p < 0.05`, `p95 ≤ 250 ms`. `amsha.rag.reranker.enabled` remains `false` by default; the gate `FAIL_CLOSED` status documents the default-disabled invariant.

## 6. Architecture & Concurrency Safeguards

1. **Session Lease Management (`OnnxSessionLeaseManager`)**:
   - Uses per-bundle monitor synchronization to serialize model loads while allowing multi-model parallel inference.
   - Reference counts active leases (`leaseCount`). Closing a session during active inference is physically impossible because `OrtSession.close()` executes only when `leaseCount == 0`.
   - Reserves each manifest's conservative resident-memory estimate before loading, enforces loaded-model and memory budgets, and evicts only the least-recently-used idle session.
   - Supports audited operator unload through `POST /api/v1/admin/local-ml/models/{bundleId}/unload`; active graph calls receive a cooperative `RunOptions.setTerminate(true)` signal, and `409` means accepted leases are still draining without the session being closed underneath them.
2. **Bounded CPU Inference Scheduler (`OnnxInferenceScheduler`)**:
   - Executes CPU inference on a bounded worker pool (`AMSHA_ONNX_INFERENCE_THREADS=2`, capacity `64`).
   - Propagates `WorkspaceContext` via `WorkspaceExecutors.wrap()`.
   - Checks absolute request deadlines before queueing, during lease acquisition, and before task execution.
3. **Reactive Non-Blocking Protection**:
   - `OnnxEmbeddingProviderFactory.rejectEventLoopThread()` inspects executing thread using `Context.isOnEventLoopThread()`.
   - Fails fast if invoked on a Quarkus event loop thread to prevent thread starvation, while permitting `@RunOnVirtualThread` virtual threads with attached Vert.x context.
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

This proves the native process starts, reports ONNX readiness, sees the verified bundle, and activates only descriptor-compatible vector indexes. `ONNX-2` calls the internal-only fixed-text probe, requires an ONNX success-counter increment, and therefore proves native `OrtSession.run()` when it passes. `ONNX-4` requires overall health `UP`, the `Vector Index Compatibility` check `UP`, and `vectorStatus=READY`; it intentionally fails for `DEGRADED` or `NOT_ACTIVE` rather than treating text fallback as proof of the Phase 1 happy path. `ONNX-5` through `ONNX-7` verify the non-activating classifier status, fail-closed enable, and internal-only mutation contracts. A native inference smoke test on every supported target remains mandatory before Phase 0 is closed. Do not rely on undocumented ORT extraction locations or system-property library overrides; they are not an approved deployment interface.

The macOS arm64 build/start/readiness, numeric corpus, and lifecycle/RSS procedure passed
on 2026-07-29, but that record predates session-admission and per-run cooperative
cancellation changes. Regenerate it for the final candidate. Linux x86-64, Linux arm64,
and signed SBOM/provenance remain required gates.

### Reusable local runtime smoke runner

Use the dedicated smoke runner for repeatable local checks. It reads only the required
`AMSHA_OMLX_*` and internal-key values from the ignored root `.env`; exported process
variables take precedence. Do not pass credentials on the command line. The OMLX
connection is exercised through its authenticated OpenAI-compatible API, while native
Amsha is explicitly configured with `AMSHA_LLM_PROVIDER=generic-openai`. The runner
also forces the embedding route to `onnx`, disables Telegram and the web channel, and
cannot select Ollama.

```bash
# OMLX authentication, model catalog, and a minimal completion
mise run smoke:local-runtime -- omlx

# OMLX plus an Amsha process that is already serving the verified ONNX bundle
mise run smoke:local-runtime -- running \
  --amsha-base-url http://127.0.0.1:8080

# Full isolated lifecycle using the freshly built native binary
mise run smoke:local-runtime -- native --binary dist/amsha-sqlite
```

The `running` and `native` commands verify bundle sizes and SHA-256 digests, overall
health, local ONNX readiness, `READY` vector-index compatibility, protected descriptor
diagnostics, fixed-text inference, and an increment of the successful-inference metric.
The native command defaults to port `18080`, uses an isolated data directory, generates
an ephemeral internal API key, and gracefully stops its child process. Logs and data are
created under the gitignored `data/smoke/` tree; they are removed after success and
retained without automatic log dumping after failure. Pass `--keep-artifacts` when a
successful native run also needs retained diagnostics, or use `--help` to see path,
port, timeout, bundle, and interpreter overrides. Set `AMSHA_SMOKE_PYTHON` to select a
different Python 3 interpreter.

This is a development smoke workflow, not a substitute for the signed, platform-bound
release evidence in Section 8. Use `scripts/verify_native_matrix.sh record` for numeric
corpus, repeated lifecycle, and RSS evidence on each supported operating system.

---

## 8. Phase 0 Release-Evidence Gates

Generate the numeric corpus with an independently installed, pinned
SentenceTransformers/PyTorch model. Offline generation is the default; a download
requires the explicit `--allow-download` option and must occur in the governed release
workflow:

```bash
python3 scripts/export_golden_corpus.py \
  --model-path /release-inputs/all-mpnet-base-v2-pytorch \
  --output data/models/golden-corpus-v1.json
```

`OnnxGoldenCorpusEquivalenceTest` compares every ONNX vector with its Python reference,
enforces the ADR's FP32/INT8 cosine floor and per-partition pass rate, requires all
canonical fixtures, exercises actual batch inference, and checks nearest-neighbour
ranking and labelled retrieval stability. In normal development it remains
model-optional. A release job sets `amsha.onnx.release-evidence=true`, which turns every
missing model/corpus prerequisite into a hard failure.

For each supported platform, build the native binary on that platform and collect a
record. `record` verifies the actual OS/architecture, launches the supplied Mach-O/ELF
binary, runs strict JVM numeric and RSS tests, performs repeated native inference while
sampling process RSS, and requires three graceful native process lifecycles:

```bash
scripts/verify_native_matrix.sh record \
  macos-arm64 ./dist/amsha-sqlite evidence/onnx/macos-arm64.json

scripts/verify_native_matrix.sh verify evidence/onnx
```

Repeat `record` on Linux x86-64 and Linux arm64 using output names
`linux-x86_64.json` and `linux-arm64.json`. `verify` fails until all three valid records
are present. These records are raw evidence inputs; they are not signatures.

Verify the release SBOM and SLSA provenance as signed blobs using the release trust
root:

```bash
scripts/verify_onnx_release_provenance.sh \
  release-trust-root.pub release.cdx.json release.cdx.sig \
  release.intoto.jsonl release.intoto.sig
```

The verifier requires CycloneDX/SPDX entries for ONNX Runtime and DJL tokenizers,
in-toto/SLSA provenance with SHA-256 subjects covering the model and tokenizer, and
valid `cosign verify-blob` signatures. The macOS numeric report, native record, and leak
samples now pass. Phase 0 remains open until the two Linux records, complete matrix,
trust root, signed SBOM, and signed provenance are retained with the release.

---

## 9. Frequently Asked Questions (FAQ)

### Q: How did you determine `AMSHA_EMBEDDING_ONNX_MODEL`, `SPACE_ID`, and `SPACE_REVISION`?
- **`AMSHA_EMBEDDING_ONNX_MODEL`**: The exact folder name under `data/models/` (`all-mpnet-base-v2-e8c3b32`), matching `"bundleId"` in `manifest.json`.
- **`AMSHA_EMBEDDING_ONNX_SPACE_ID`**: Read from `"spaceId"` inside `"embedding"` in `manifest.json` (`sentence-transformers/all-mpnet-base-v2`).
- **`AMSHA_EMBEDDING_ONNX_SPACE_REVISION`**: Read from `"spaceRevision"` in `manifest.json` (`e8c3b32edf5434bc2275fc9bab85f82640a19130`), which is the Hugging Face Git commit SHA.

### Q: Does a successful native build prove ONNX inference works in the native binary?
No. A native image can compile while a JNI resource, dynamic-library loader, reflection path, or tokenizer binding still fails at runtime. Follow the native build, launch, and REST procedure in Section 7; a passing `ONNX-2` is the real inference proof for that built target. Phase 0 remains open until both Linux targets and the remaining signed supply-chain gates pass.

### Q: How do I validate local ONNX inference over REST?
Run the REST validation script:
```bash
bash rest-http/scripts/run_all_validations.sh --onnx
```
This executes `ONNX-1` (runtime health), `ONNX-2` (an internal fixed-text inference probe plus ONNX success-metric increment), `ONNX-3` (model bundle size/digest verification), `ONNX-4` (active vector-index descriptor compatibility), and `ONNX-5` through `ONNX-7` (Phase 3A classifier control status, missing-implementation rejection, and internal authorization). `ONNX-4` must report `READY` for this model-installed happy-path validation. If it reports `MISSING_METADATA` or `MISMATCH`, keep text fallback active, stop the application, and perform the explicit pre-production reset of the configured `AMSHA_DATA_DIR` or development database volume; never auto-adopt a non-empty index. The focused JVM inference proof is separate:

```bash
./mvnw -pl local-ml-onnx -Dtest=OnnxEmbeddingModelSmokeTest test
```
