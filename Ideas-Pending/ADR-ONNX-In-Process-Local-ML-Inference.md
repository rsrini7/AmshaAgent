# ADR: In-Process Local Machine-Learning Inference with ONNX Runtime

- Status: Accepted for Phases 0–1; Proposed for Phases 2–3
- Date: 2026-07-27
- Last reviewed: 2026-07-28
- Last updated: 2026-07-28
- Decision owners: Amsha Agent maintainers
- Related invariants: [Architecture Invariants](../docs/Architecture_Invariants.md)
- Decision maturity: Phase 0 spike and Phase 1 implementation are authorized subject
  to the Section 15 go/no-go gates; Phases 2–3 remain proposed

## Current implementation status (2026-07-28)

Phase 0–1 foundation work is in progress. ONNX is available only as an explicit
provider slot with an installed verified bundle; it does not change the configured
default provider list and has not yet passed real-model golden-corpus validation.

- Completed: provider-neutral `EmbeddingSpaceDescriptor` support and compatible-slot
  enforcement in the existing embedding failover loop; incompatible slots are skipped
  rather than mixing vector spaces.
- Completed: optional `local-ml-onnx` module wiring, pinned ONNX Runtime Java
  `1.27.0`, JVM native-load probe, CDI/Jandex setup, central native reflection
  registration, feature-disabled startup behavior, and managed runtime-environment
  cleanup during CDI shutdown.
- Completed: local bundle manifest parsing and verification for an explicitly selected
  bundle below `amsha.onnx.model-root`. Model and tokenizer files must be regular
  root-contained files with matching SHA-256 digests and sizes; path traversal,
  symlink escapes, oversized files, tampering, and manifest/directory ID mismatches
  are rejected. Unknown manifest metadata is tolerated for forward compatibility while
  all fields used by the current verifier remain mandatory and validated. The module
  never downloads or installs model assets automatically.
- Completed for the JVM spike: selected `ai.djl.huggingface:tokenizers` `0.33.0`
  (Apache-2.0) for standard Hugging Face WordPiece, BPE, and Unigram tokenizer JSON.
  Its CPU JNI artifact packages Linux x86-64, Linux arm64, and macOS arm64 libraries.
  `OnnxTokenizerFactory` forces DJL offline mode and telemetry opt-out, and rejects
  native-library/flavour overrides before it loads a bundle-local tokenizer. A local
  macOS arm64 load and WordPiece tokenization probe passes; this is not yet the full
  supported-platform or native-image gate.
- Completed Phase 1 lifecycle foundation: verified bundles acquire cached ORT sessions
  only through idempotent `AutoCloseable` leases. Per-bundle locking rejects new leases
  once unload begins and never closes a session while an active lease remains; bounded
  unload/shutdown reports draining leases for process teardown rather than racing
  `OrtSession.run()`.
- Completed Phase 1 scheduling foundation: CPU inference work uses a fixed, bounded,
  workspace-context-aware executor. Full queues fail explicitly, and work that reaches
  the executor after its request deadline completes as a timeout.
- Completed Phase 1 bundle metadata foundation: embedding bundles can carry a typed,
  validated tokenizer/input/output/pooling/normalization/dimension/vector-space contract;
  older non-embedding manifests remain verifiable but cannot be selected by the future
  embedding provider.
- Completed Phase 1 inference core: a bundle-contract-driven batch runner creates and
  closes owned input tensors and `OrtSession.Result`, validates exact graph input/output
  names, performs declared pooling, ignores padded tokens for masked means, and applies
  checked L2 normalization.
- Completed Phase 1 opt-in embedding integration: `OnnxEmbeddingProviderFactory` uses
  only a verified embedding bundle, requires runtime readiness, preserves the manifest
  vector-space descriptor, enforces UTF-8 segment/batch limits, and runs tokenization
  under the same lifecycle lease as its `OrtSession`. It is selected only by adding
  `onnx` to the existing provider slot list and supplying its bundle ID.
- Completed development bundle onboarding: `mise run models:install-mpnet` installs
  the pinned Apache-2.0 MPNet ONNX/tokenizer assets below the gitignored model root,
  verifies fixed sizes and SHA-256 values, and writes the manifest verified by a
  model-optional real-JVM smoke test through the provider SPI, bounded scheduler,
  tokenizer/session lease, and `OrtSession.run()`.
- Completed embedding warm-up: a configured bounded subset of manifest-declared,
  non-sensitive probes runs through the same scheduler and lease before an ONNX slot
  is created. Failed warm-up rejects that slot rather than serving a cold/broken bundle.
- Completed initial observability: payload-free ONNX request latency/outcome and warm-up
  outcome metrics use only fixed outcome labels; optional-runtime readiness exposes a
  status without making an otherwise valid fallback deployment unready.
- Verified so far: focused RAG compatibility tests, ONNX runtime/bundle verifier tests,
  application assembly compilation, documentation validation, and diff hygiene.
- Still required before ONNX embedding support can be generally available: tokenizer platform/native
  evaluation, a real signed model/index installation flow, golden-corpus correctness
  evidence, long-running resource/leak observability, and vector-index fingerprint parity across SQLite,
  PostgreSQL, and filesystem.
- Explicitly not started: Phase 2.5 core classifier SPIs and every Phase 2–3
  classifier behavior. No database migration is introduced at this stage because no
  ONNX-produced vector schema is active.

## 1. Decision summary

Amsha Agent will evaluate ONNX Runtime Java as an optional, in-process CPU inference
engine. The first production use case is local text embeddings. Intent routing,
semantic tool-risk detection, and answer-quality scoring remain experiments until
each has a versioned dataset, calibrated thresholds, and an independently approved
rollout.

### Vector-index backend scope (current state)

All three persistence backends already have vector-search paths:

- SQLite uses sqlite-vec/vec0 through `RagSchemaInitializer`,
  `ArchivalVecSchemaInitializer`, and `GlobalKnowledgeBase`.
- PostgreSQL uses pgvector through `rag_chunk_embedding`, vector columns in archival
  memory, `RagChunkJdbiDao`, and `PostgresMemoryRetrievalAdapter`.
- Filesystem persistence uses Lucene `KnnFloatVectorField` and hybrid search through
  `LuceneIndexManager` and `FsMemoryRetrievalAdapter`.

Filesystem reconstruction from JSON currently rebuilds Lucene documents without
vectors; Phase 1 must correct new-schema reconstruction so it regenerates embeddings.
It is a recovery gap, not evidence that filesystem vector search is absent.

The repository requires persistence parity. Phase 1 therefore introduces matching
fingerprint schema/manifest support and reset/rebuild behavior for SQLite, PostgreSQL,
and filesystem vector namespaces in the same change. Existing development data is not
migrated; the explicit reset policy in Section 7 applies. ONNX vector support is not
declared generally available until parity tests pass on all three backends.

The integration must:

- remain optional and disabled unless a verified model bundle is installed;
- preserve all deterministic routing, authorization, `ToolInvocationGuard`, and
  human-approval rules;
- perform no inference, model loading, download, or filesystem I/O inside a
  transaction;
- keep model identity and preprocessing identity with every vector index;
- use bounded concurrency, bounded input sizes, timeouts, metrics, and circuit
  breakers;
- support JVM mode first; native-image support is a release gate, not an assumption;
- never download executable model assets implicitly during request handling.

This ADR approves an implementation spike and phased rollout. It does not approve
shipping an unverified classifier as an authorization boundary.

## 2. Why this is useful

Small local models reduce network latency for embeddings and classification, keep
memory retrieval usable when remote embedding services are unavailable, and align with
Amsha's single-process deployment goal without requiring a Python runtime in production.

Local transformer embedding is intended for chunks no longer than the bundle
manifest's model limit (commonly 512 tokens), not a raw long document. Document
ingestion must split long content using the fingerprinted chunking strategy before
inference. The byte limits in configuration are abuse/resource ceilings, not a claim
that a 256 KB segment is a useful model input. A remote provider remains an optional
fallback only when another slot is configured in
`amsha.infinite-loop.embedding.providers`; ONNX-only deployments intentionally have
no remote fallback. Vector failover additionally requires the fallback slot to
advertise a verified compatible embedding-space descriptor.

Cloud VM CPU compute is not inherently cheaper than API calls; primary cost and latency
wins apply to high-volume local embedding recall, latency-critical micro-routing, and
offline execution isolation rather than a blanket cloud VM cost-reduction claim.

Expected benefits must be demonstrated by benchmarks. Figures such as 5 ms routing
or 20 ms embedding are targets, not architecture guarantees; performance varies by
model, sequence length, batch size, CPU, execution provider, warm-up state, and
quantization.

## 3. Scope and rollout order

### Phase 0: compatibility and tokenizer spike

Prove all of the following before adding ONNX to a default distribution:

1. **Tokenizer Binding Selection**: Evaluate candidate tokenizer implementations
   (Deep Java Library `ai.djl.huggingface:tokenizers` JNI over Rust, HuggingFace
   C++ JNI bindings, or pure-Java tokenizers) against criteria: permissive license
   (allowlisted SPDX licenses: `Apache-2.0`, `MIT`, `BSD-2-Clause`, `BSD-3-Clause`,
   `ISC`, or `0BSD`), GraalVM native-image JNI viability, performance, and model
   family coverage (WordPiece, BPE, Unigram for BERT/RoBERTa/MPNet).
   Any other license requires an explicit legal and maintainer review.
   The current JVM spike selects `ai.djl.huggingface:tokenizers` `0.33.0`
   (Apache-2.0). It is constrained to the bundled CPU JNI libraries and local
   `tokenizer.json` files by `OnnxTokenizerFactory`, which enforces DJL offline mode
   and telemetry opt-out before any tokenizer class is used. This selection remains
   conditional on the cross-platform and native-image gates below.
2. **Platform Native Loading**: Verify ONNX Runtime and selected tokenizer native
   libraries load cleanly on JDK 25 across Linux x86-64, Linux arm64, and macOS arm64.
3. **Memory Leak Gate**: Repeated application startup, inference cycles, and shutdown
   must run without native memory growth or unclosed handle leaks.
4. **Golden Reference Tolerances**: Establish reference outputs using Python
   SentenceTransformers / `optimum-cli` exporters. The initial acceptance targets are
   cosine similarity of at least 0.9999 for FP32 models and at least 0.995 for INT8
   models on a versioned corpus. Phase 0 must confirm or revise those provisional
   thresholds using per-example distributions, nearest-neighbour ranking stability,
   and task-level retrieval quality rather than reporting only a corpus average. At
   least 99.5% of examples in every declared corpus partition must meet the applicable
   cosine target, with no systematic failure cohort. Cohorts include language/script,
   Unicode category, input-length bucket, empty/very-short inputs, and truncation/window
   behavior. Canonical contract fixtures must pass 100%. Any revised target or pass
   rate requires recorded evidence and ADR approval before Phase 1.
5. **Native Binary Gate**: A native binary can load, infer, and shut down on every supported
   native target, or native builds exclude the feature with an explicit startup log message.
6. **Supply Chain & Licensing**: Dependency licenses, model licenses, provenance,
   SHA-256 checksums, and SBOM entries are fully verified.

### Phase 1: local embeddings

Implement `OnnxEmbeddingProviderFactory` (implementing `com.amsha.rag.EmbeddingProviderFactory`)
and register `"onnx"` as an explicit candidate in `amsha.infinite-loop.embedding.providers`.
Do not silently alter the existing default provider list during the spike.

This phase includes model installation, health reporting, index compatibility,
batch inference (`embedAll`), observability, and an explicit development reset/rebuild
workflow.

### Phase 2: shadow-mode classifiers

Run intent, tool-risk, or answer-quality classifiers without changing active agent behavior.
Record only privacy-safe scores and compare them with labelled outcomes. Promote a
classifier only after its model card, dataset, calibration, false-positive and
false-negative budgets, and rollback criteria are approved.

### Phase 2.5: core classifier SPI gate

Before any classifier affects behavior, land and review the additive post-policy
tool-risk SPI and ordered answer-quality interceptor SPI in `core`, with no active ONNX
implementation. Focused tests must prove ordering, monotonic decision composition,
HITL preservation, no retry across a tool boundary, and behavior when no optional
implementation exists. This gate does not block Phase 0–1 embeddings.

### Phase 3: bounded behavioral use with kill switches

- **Intent Routing**: May choose a fast path only above a calibrated confidence threshold;
  all other cases fall back to the existing LLM routing flow.
- **Semantic Tool-Risk**: May add a block or require human approval. It may **never**
  return `Allow`, reduce risk, bypass role rules, or bypass HITL.
- **Answer-Quality**: May request one bounded retry without creating an unbounded retry
  loop, preserving coordinator `FINISH` and tool ordering invariants.
  A retry is permitted only in a response-only turn before any tool invocation has
  occurred in that turn. It may not cross a tool-invocation boundary, re-enter tool
  selection, or repeat a tool's side effects. After a successful tool result, the
  coordinator emits `FINISH`; answer scoring may observe/report it but cannot retry it,
  and no later tool call may occur in that turn.
- **Per-Classifier Kill Switches**: Each classifier feature MUST expose an independent
  kill switch (`amsha.onnx.classifier.<name>.enabled=false`). Startup configuration is
  the baseline. Restart-free switching additionally requires an internal-only admin
  control plane backed by an application-scoped atomic state holder; injected
  `@ConfigProperty` values alone are not dynamically refreshed. Disabling takes effect
  for new requests and drains in-flight inference without changing its result into an
  authorization decision.

## 4. Module and component architecture

Create a dedicated CDI library module, `local-ml-onnx`, rather than placing native runtime
ownership in `core` or `rag`. The module depends on existing and Phase 2.5 SPIs:

- `com.amsha.rag.EmbeddingProviderFactory` (for RAG embeddings);
- `dev.langchain4j.model.embedding.EmbeddingModel` (LangChain4J interface);
- the additive post-policy tool-risk SPI introduced in `core` for semantic guardrails.

Component layout:

- `OnnxRuntimeManager`: owns the ORT environment, cached sessions, tokenizers, executors,
  and health state;
- `OnnxEmbeddingProviderFactory`: implements `com.amsha.rag.EmbeddingProviderFactory`;
- a provider-neutral `EmbeddingSpaceDescriptor` in `rag`, implemented by every
  embedding factory and used to bind an index namespace to one verified vector space;
- classifier adapters implementing `IntentRoutingProvider` and
  a new additive post-policy tool-risk SPI;
- an ordered answer-quality interceptor SPI added to `core` before enabling that use
  case; adding a second `AnswerGuardStrategy` CDI bean directly would be ambiguous;
- One central module reflection configuration (`AmshaLocalMlReflectionConfig`).

The existing `ToolGuardInterceptor` chain currently executes inside
`ToolInvocationGuard` before its built-in validation and authorization checks, so it
is not the correct activation point for the ONNX risk classifier. In Phase 2.5,
`core` must add an additive post-policy hook inside `ToolInvocationGuard`. It runs only
after the mandatory deterministic checks have produced a provisional `Allow`, and may
convert that result only to `Blocked` or `PauseForApproval`; it cannot inspect or
replace an existing block/approval/deduplication decision. This preserves the rule
that every tool passes through the guard while ensuring ML can only increase risk.

An ML-requested approval must reuse the existing
`ToolInvocationGuard.GuardDecision.PauseForApproval` and `ApprovalFrame` execution
path. It must not introduce a reflex or Telegram callback; the existing approval
path's notification and restart semantics remain unchanged.

The Phase 2.5 SPI shapes are:

```java
public interface PostPolicyToolRiskInterceptor {
    int priority(); // lower runs first

    ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context);
}

public enum ToolRiskAction {
    REQUIRE_APPROVAL,
    BLOCK
}

public interface AnswerQualityInterceptor {
    int priority(); // lower runs first

    ClassifierResult<AnswerQualityAction> evaluate(AnswerQualityContext context);
}

public enum AnswerQualityAction {
    REQUEST_RETRY
}
```

Implementations are ordered by ascending `priority()`, then binary class name as a
stable tie-breaker. Tool-risk results compose monotonically (`BLOCK` outranks
`REQUIRE_APPROVAL`, which outranks `NoOpinion`); no action represents `Allow`.
Answer-quality interceptors run only after the authoritative `AnswerGuardStrategy`
accepts a response-only candidate, and multiple retry requests consume the same
single retry budget. Context records belong in `core`, are immutable, expose only the
minimum required data, and carry whether the current turn has invoked a tool.

The core dispatcher isolates each optional interceptor. A timeout or unchecked
exception becomes `NoOpinion(INFERENCE_ERROR)` (or `TIMEOUT`), increments a bounded
metric, and continues the existing authoritative path without logging sensitive
context. Errors from one optional interceptor do not bypass or re-run deterministic
checks, alter a pre-existing guard decision, or fail the entire turn.

Follow the new-CDI-module rules: Jandex, `META-INF/beans.xml`, application indexing,
test configuration, and startup-log verification. The module must not depend on
`amsha-app`; the application assembles it. Optional discovery across module boundaries uses
`BeanManager.getBeans(Object.class)`, class-name checking, and `getReference()`.

### Classifier "No Opinion" SPI contract

To prevent any ambiguity or treating an error/fallback as a low-confidence `Allow`,
classifier adapters must implement a strict sealed result interface. This contract
belongs in `core` (or a neutral API module), not in `local-ml-onnx`, so core callers
do not depend on the optional implementation module:

```java
public sealed interface ClassifierResult<T> {
    record Opinion<T>(T value, float confidence, String ruleId) implements ClassifierResult<T> {
        public Opinion {
            java.util.Objects.requireNonNull(value, "value");
            if (!Float.isFinite(confidence) || confidence < 0.0f || confidence > 1.0f) {
                throw new IllegalArgumentException("confidence must be finite and in [0, 1]");
            }
            if (ruleId == null || ruleId.isBlank()) {
                throw new IllegalArgumentException("ruleId must not be blank");
            }
        }
    }

    record NoOpinion<T>(NoOpinionReason reason) implements ClassifierResult<T> {
        public NoOpinion {
            java.util.Objects.requireNonNull(reason, "reason");
        }
    }

    enum NoOpinionReason {
        DISABLED,
        MISSING_MODEL,
        TIMEOUT,
        OVERLOAD_REJECTED,
        CIRCUIT_OPEN,
        LOW_CONFIDENCE,
        INFERENCE_ERROR
    }
}
```

A `NoOpinion` result MUST be treated by callers as "no local-classifier signal
present" and MUST pass control entirely to the existing authoritative fallback flow.
The hierarchy is deliberately closed to `Opinion` and `NoOpinion`; operational states
such as deferral belong in `NoOpinionReason`. A third result shape is an SPI-breaking
change and requires a versioned contract update and exhaustive-switch migration.

```text
Application
  |
  +-- existing deterministic routing and guards (always authoritative)
  |
  +-- local-ml-onnx (optional)
        |
        +-- verified model registry
        +-- bounded inference scheduler
        +-- embedding adapter -> com.amsha.rag.EmbeddingProviderFactory
        +-- intent classifier -> IntentRoutingProvider
        +-- tool-risk classifier -> ToolInvocationGuard post-policy hook
        +-- answer scorer -> ordered answer-quality interceptor
```

## 5. Model bundles and supply-chain policy

A model is an immutable bundle, not an arbitrary path to an `.onnx` file. Its
manifest must contain:

- logical model ID and immutable revision;
- ONNX file SHA-256 and byte size;
- tokenizer/config file checksums;
- source repository and export command/tool versions;
- model and dataset licenses;
- task, input names, output name, maximum sequence length, vector dimension, and
  chunking strategy (`MEAN_OVERLAPPING_WINDOWS`, `FIRST_MAX_TOKENS`, or
  `SLIDING_WINDOW`), including window overlap and multi-window aggregation;
- tokenization, truncation, padding, pooling, normalization, and quantization rules (with quantization toolchain provenance and fidelity bounds verification);
- training/evaluation provenance; classifier bundles additionally require a model
  card and adversarial prompt-injection robustness documentation.

Chunking strategies have fixed meanings:

- `FIRST_MAX_TOKENS` embeds only the first manifest-limited token window and is an
  explicitly lossy option for short/query-like inputs.
- `MEAN_OVERLAPPING_WINDOWS` creates manifest-sized windows at the declared overlap,
  embeds each window, and returns one L2-normalized weighted-mean vector using the
  manifest's weighting rule.
- `SLIDING_WINDOW` emits independently addressable child chunks/vectors at ingestion
  and is valid only on one-to-many ingestion paths; direct single-vector embedding
  must reject it unless the manifest declares a separate query aggregation rule.

`EmbeddingModel.embedAll` must always return exactly one embedding per input segment,
in input order. Therefore `SLIDING_WINDOW` expansion belongs to the ingestion
orchestrator before it calls the provider; each generated child is a separate input
with stable source/offset metadata. The ONNX provider itself never changes batch
cardinality. `MEAN_OVERLAPPING_WINDOWS` remains a provider-level one-to-one operation.

### Trust and provenance model

Every file is verified by a pinned SHA-256 digest in `models-index.json`. For remotely
maintained indexes, the canonical index is additionally signed with Ed25519 by a
trusted, versioned key. The installer must define canonical serialization, key
rotation/revocation, downgrade protection, and behavior for an unknown key. A
signature does not replace per-file digests.

The initial trust-root keyring is embedded in the signed application distribution and
identified by stable key IDs. Rotation uses an application update containing an
overlap of old and new keys before the old key is revoked. An unknown/revoked key,
invalid signature, or index version lower than the highest accepted version fails
closed. Digest-only operation is permitted only for a model index bundled in that
same signed application distribution; it is never a fallback for a remotely obtained
index.

Production configuration selects a bundle ID. Arbitrary remote URLs are forbidden.
Model files larger than the bundle threshold live outside the JAR in a configurable
model directory. Installation is an explicit onboarding/admin action that downloads
to a temporary file, enforces time and size limits, verifies every required digest
and index signature, and atomically publishes the complete bundle. A failed or
partial download is never loadable.

The installer must use a governed outbound-network path and preserve mandatory HITL
for `NETWORK_OUTBOUND`. Any mutating `/admin/**` installer endpoint must enforce
`isInternal()` and must not accept an arbitrary URL from the request.

No model is downloaded on startup or on a user request by default. Offline mode performs
zero network calls.

## 6. Embedding correctness contract

Correct embeddings require the exported model's preprocessing and postprocessing,
not merely `session.run()`:

1. Apply the manifest's tokenizer revision and special-token rules.
2. Enforce `amsha.onnx.max-input-bytes` per text segment and a separate
   `amsha.onnx.max-batch-input-bytes` limit before tokenization. Raw byte count is not
   a reliable memory estimate, so both limits apply in addition to token, tensor, and
   queue budgets. Count each original UTF-8 segment once against these byte limits;
   generated windows are bounded separately by the manifest token/window limits and
   the provider-internal and ingestion child-window limits.
3. Truncate or create provider-internal windows according to the manifest. For
   `MEAN_OVERLAPPING_WINDOWS`, combine window embeddings using the declared weighting
   rules. For `SLIDING_WINDOW`, require the ingestion orchestrator to expand the
   segment first; reject an unexpanded over-limit direct call rather than silently
   changing output cardinality.
4. Supply all inputs required by the graph, such as `input_ids`, `attention_mask`,
   and, only when declared, `token_type_ids`.
5. Select outputs by manifest name and validate rank and dimensions; do not assume output index zero.
6. For token embeddings, perform attention-mask-aware pooling so padding contributes nothing. Reject an all-zero mask.
7. Apply L2 normalization when required by the model contract.
8. Batch `embedAll`; do not loop through single-item inference calls.
9. Close every adapter-created input tensor and `OrtSession.Result` with
   try-with-resources, including exceptional paths. The adapter owns tensors it
   creates; `OrtSession.Result` owns its returned values. Borrowed environment/session
   handles and any caller-owned values are not closed by an inference invocation.

Golden tests compare Java output with the reference exporter implementation for
empty text, Unicode, long/truncated text, padding, multi-window text, and multi-item
batches, adhering to the tolerance and retrieval-quality bounds accepted in Phase 0.

## 7. Vector-index compatibility and development reset policy

Dimension equality is insufficient. Every stored vector namespace/index must record
an embedding fingerprint derived from:

- canonical embedding-space ID/fingerprint and immutable model artifact revision
  (the bundle ID/revision for ONNX);
- tokenizer revision;
- pooling and normalization rules;
- output dimension and numeric precision;
- chunking algorithm/version, window parameters, and multi-window aggregation.

### Embedding-space failover invariant

Every `EmbeddingProviderFactory` must expose an immutable
`EmbeddingSpaceDescriptor` containing slot/provider provenance plus a canonical space
ID, immutable model artifact revision, tokenizer/pre/postprocessing revisions,
dimension, precision, and the resulting space fingerprint. Provider/transport
identity is provenance but is not part of the space fingerprint, allowing two
independently hosted implementations of the same verified space to be compatible.
Two slots may share a canonical space ID only after golden-corpus equivalence meets
the Phase 0 contract; matching dimensions or model display names alone are
insufficient.

A factory that cannot attest an immutable artifact revision—such as a mutable remote
model alias—must use a provider-bound space ID and cannot claim compatibility with
another slot. An operator-managed space epoch may make such changes explicit, but an
epoch change always triggers fingerprint mismatch and the development reset/rebuild
policy below. Compatibility
probes may quarantine drift; they do not turn an unverifiable alias into an immutable
revision.

`InfiniteLoopEmbeddingModel` may rotate an ingestion or query call only to a slot
whose descriptor is compatible with the index namespace. Phase 1 must validate the
configured slot set at startup and expose the active space in diagnostics. An
incompatible provider is not a vector fallback: on compatible-slot exhaustion, fail
that vector operation clearly and use the backend's non-vector retrieval path where
available. Never label a vector with a configured model when a different runtime slot
actually produced it.

Index construction pins one explicit descriptor and does not use unrestricted
provider rotation. This rule is provider-neutral and also prevents existing
remote-provider loops from mixing embedding spaces.

Queries may use an index only when fingerprints match. A mismatch must fail that
vector path clearly while allowing safe non-vector retrieval where available.

### Development reset and schema policy

The project is pre-production, and existing local data is disposable. Phase 1 does
not build an online data migration, dual-write path, versioned cutover, or rollback
journal. It establishes the correct schema and fingerprint contract for newly created
indexes:

- **SQLite and PostgreSQL**: Add matching Flyway migrations in the next shared
  version (currently `V10`) rather than editing an already applied migration. The
  migrations create SQL-backed fingerprint metadata and PostgreSQL vector schema with
  equivalent semantics. SQLite vec0 virtual tables that require the loaded extension
  continue through the existing schema-initializer flow, updated to consume and
  validate the same metadata contract.
- **Filesystem**: Add a versioned, checksummed index manifest containing the
  fingerprint and vector dimension. On an empty/new data directory, build Lucene with
  that manifest and regenerate vectors whenever JSON source records reconstruct the
  index; do not retain the current null-vector rebuild behavior.
- **Session-local indexes**: Create them under the current descriptor whenever the
  workspace/session starts; no durable migration is required.

Before implementation, inventory every vector namespace—including SQLite RAG,
archival/global/session paths, PostgreSQL RAG/archival paths, and filesystem Lucene—
and classify it as durable, rebuildable, or session-local. Every durable/rebuildable
namespace stores and validates the descriptor fingerprint.

Startup behavior is explicit:

1. An empty index initializes with the configured descriptor.
2. A non-empty index with a missing or mismatched fingerprint disables only vector
   retrieval, preserves safe BM25/FTS behavior, and prints an actionable development
   reset instruction. The application never deletes data automatically.
3. Developers reset SQLite/filesystem state by deleting the configured development
   data directory and reset PostgreSQL through the existing development database/
   volume recreation flow. These are deliberate operator actions, not startup side
   effects.
4. After reset, normal bootstrap/Flyway creates the new schema and all new ingestion
   writes carry the active fingerprint.

All three backends MUST pass contract tests for empty initialization, fingerprint
mismatch, explicit reset/rebuild, filesystem restart reconstruction with vectors, and
non-vector fallback before Phase 1 ships. If preserving production data becomes a
requirement, design online re-embedding and cutover in a separate ADR before the first
stable release; do not silently expand this development reset policy.

## 8. Runtime lifecycle, concurrency, and failure behavior

- Pin exact Maven versions through root dependency-management properties; version ranges are forbidden.
- Create runtime objects after application startup, never in static initializers.
- Treat the shared ORT environment according to documented ownership rules. Close owned sessions, session options, tokenizers, tensors, and executors exactly once in `@PreDestroy`.
- **Session concurrency primitives**: Sessions are cached by immutable bundle ID.
  Acquisition returns an `AutoCloseable` lease. Acquire, transition to closing, and
  final close form one per-bundle state machine under the same lock; once closing
  begins, new leases are rejected, and the session closes only after all existing
  leases return. A bare `AtomicInteger` or cache removal listener is insufficient
  because check/increment/evict can race. A cache may select eviction candidates but
  must delegate closure to this lease protocol.
- Lease acquisition shares the inference request's absolute deadline and never blocks
  beyond it. A full scheduler queue returns `OVERLOAD_REJECTED`; an accepted request
  that cannot acquire its lease before the deadline returns `TIMEOUT`. Embedding
  adapters surface either as a provider failure so rotation can proceed, while
  classifiers map them to the corresponding `NoOpinionReason`.
- Eviction uses pure LRU among idle sessions, ordered by a monotonic last-access
  sequence and then bundle ID as a deterministic tie-breaker. Bundle size is an
  admission constraint, not an LRU weight: evict oldest idle sessions until both
  `max-loaded-models` and the conservative resident-memory budget admit the new
  bundle. If no candidate is idle, reject the new load rather than closing an active
  session. Do not trigger eviction from noisy process-RSS samples.
- Explicit operator unload uses a mutating
  `/admin/local-ml/models/{bundleId}/unload` endpoint guarded by `isInternal()` and an
  audit event. It rejects new leases, requests cooperative cancellation, waits up to
  `shutdown-grace`, and returns a conflict without closing the session if leases
  remain. Application shutdown also rejects new leases and signals cancellation, but
  after the grace period it drops late results and continues shutdown. It must not
  call `OrtSession.close()` concurrently with an active `run()` unless Phase 0 proves
  that exact binding/version race safe; otherwise process teardown reclaims the
  remaining native handles and emits a diagnostic.
- Use a dedicated bounded executor/queue and configurable concurrency. Workspace-aware asynchronous work uses `ContextAwareExecutorService` or `WorkspaceScopeHolder.wrap()`.
- Never block a Quarkus event-loop thread. Define synchronous and asynchronous adapter boundaries explicitly.
- Embedding failures feed the existing `InfiniteLoopProvider` slot health/cooldown
  mechanism; do not add a competing ONNX-only breaker for the same slot. Standalone
  classifiers use a per-bundle `CLOSED`/`OPEN`/`HALF_OPEN` breaker: model load errors,
  inference errors, and inference timeouts count toward the threshold; queue rejection
  does not. While open, return `NoOpinion(CIRCUIT_OPEN)` without scheduling work.
  After the cooldown, admit one half-open probe; success closes the breaker and failure
  reopens it. The kill switch always takes precedence.
- **Warm-up semantics**: Before the factory exposes the ONNX slot, run a configurable,
  bounded set of deterministic, non-sensitive probe strings declared in the signed
  bundle manifest, including the expected output shape and finite/norm checks. Do not
  use random tokens or user data. Run the manifest probes within a configurable time
  budget. A failure makes factory initialization fail so the existing provider
  producer skips that slot.
  Runtime slot removal/recovery requires an explicit extension to
  `InfiniteLoopEmbeddingModel`; it must not be claimed as existing behavior. Report
  the feature as unavailable in its health details without making the entire
  application unready when another configured embedding slot is valid.

### Failover & Rotation

For embeddings, a local failure is reported to `InfiniteLoopEmbeddingModel` so its
existing provider rotation can select another configured, fingerprint-compatible
slot. An arbitrary OpenAI/Jina/Ollama model is not assumed compatible with an ONNX
model; when no compatible slot remains, the backend uses non-vector retrieval rather
than querying with a mismatched vector.
For an optional classifier, timeout, overload, missing assets, or inference failure returns
`ClassifierResult.NoOpinion` and preserves the existing authoritative fallback path.

## 9. Security and privacy

ONNX models and native libraries are executable supply-chain inputs. Only allowlisted
bundle manifests with verified digests and, when the index is remote, a valid index
signature may load. Paths must be canonicalized under
the configured model root; symlinks and path traversal must not escape it.

Classifier inputs can contain secrets. Raw prompts, tool parameters, tokens, and
embeddings must not be logged or attached to metrics/traces. Audit events may record that an ML signal
contributed to a decision, its calibrated risk band, and the immutable model revision,
but not the sensitive payload.

Semantic guardrails are defense in depth, not a security boundary. Model cards for tool-risk classifiers must document prompt-injection adversarial robustness testing. Existing checks for `NETWORK_OUTBOUND`, `EXECUTE_SHELL`, trusted roles, tool capabilities, sandboxing, and HITL remain authoritative and execute regardless of classifier output.

## 10. Native-image and packaging gate

Do not assume that reflection and JNI configuration alone make ONNX Runtime Java or
the tokenizer binding native-image compatible. Phase 0 determines whether native-library extraction, class initialization, JNI registration, resource lookup, and platform classifiers work inside the produced Quarkus native executable.

When native support is viable:

- put package-wide run-time initialization in `app/src/main/resources/META-INF/native-image/com.amsha/app/native-image.properties`;
- add only proven reflection/JNI/resource registrations, using the owning module's central configuration and exact binary names;
- package the correct libraries per operating system and architecture;
- verify extraction/loading from a read-only application directory and a writable, no-exec temporary-directory scenario;
- run a native smoke test that loads the real bundle, checks golden results against
  the Phase 0 per-example FP32/INT8 cosine thresholds and pass-rate contract, and
  exercises shutdown.

If those gates fail, JVM distributions may offer the feature while native builds exclude it at build time. Native startup states local ML is unavailable and continues only when configured fallbacks are valid.

## 11. Configuration contract

Configuration properties are explicitly reconciled:

```properties
# Global ONNX Feature Toggle & Limits
amsha.onnx.enabled=false
amsha.onnx.model-root=data/models
amsha.onnx.max-model-file-bytes=1073741824
amsha.onnx.max-loaded-models=2
amsha.onnx.max-resident-mb=1024
amsha.onnx.inference-threads=2
amsha.onnx.queue-capacity=64
amsha.onnx.timeout=PT5S
amsha.onnx.shutdown-grace=PT10S
amsha.onnx.max-input-bytes=262144
amsha.onnx.max-batch-input-bytes=1048576
amsha.onnx.max-batch-size=32
amsha.onnx.max-internal-windows-per-segment=64
amsha.onnx.warmup.enabled=true
amsha.onnx.warmup.max-probes=5
amsha.onnx.warmup.timeout=PT3S
amsha.onnx.classifier.circuit.failure-threshold=3
amsha.onnx.classifier.circuit.cooldown=PT30S

# Provider-neutral embedding-index safety
amsha.rag.embedding-index.max-child-windows-per-segment=64

# RAG embedding integration; additional vector-failover slots must advertise the same space
amsha.embedding.onnx.model=sentence-transformers-all-mpnet-base-v2-fp32

# Per-Classifier Kill Switches (default: false)
amsha.onnx.classifier.intent.enabled=false
amsha.onnx.classifier.tool-risk.enabled=false
amsha.onnx.classifier.answer-quality.enabled=false
```

`max-input-bytes` and `max-batch-input-bytes` count UTF-8 encoded bytes, not Java
characters or tokens. The manifest token limit remains independently authoritative.

Every `@ConfigProperty` MUST appear in `application.yaml`, `.env.example`, and the module README. CPU is the portable baseline. GPU execution providers require separate artifacts, configuration, compatibility tests, and fallback to CPU.

## 12. Observability and operations

### Memory accounting

Neither the standard/extended operating-system MXBeans nor ONNX Runtime Java expose
portable, authoritative per-session resident memory accounting. Do not depend on a
non-existent `OrtEnvironment.getNativeMemoryAllocated()` API. Enforce admission using
verified bundle sizes, configured per-bundle working-memory estimates, loaded-model
count, tensor/queue budgets, and a process-wide safety margin. Export process RSS from
the platform telemetry available in each distribution as a best-effort diagnostic;
validate leak behavior with OS tooling, Native Memory Tracking where applicable, and
long-running stress tests. `amsha.onnx.max-resident-mb` is therefore a conservative
admission budget, not a precise RSS circuit breaker.

### Bounded metrics and fixed cardinality

Metrics MUST use bounded-cardinality label rules:

- Model labels are limited to configured, allowlisted bundle aliases with an `_other`
  fallback; immutable revisions and fingerprints are not metric labels.
- Decision counters use the disjoint score bands `[0.0, 0.5)`, `[0.5, 0.85)`, and
  `[0.85, 1.0]`; the last band explicitly includes the validated maximum 1.0.
- Calibration telemetry also records scores in a fixed-boundary histogram with finer
  cumulative upper bounds `0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0`.
  The metrics backend derives p50/p95/p99 over time without adding model revision
  labels. Bands support alert routing; the histogram and protected, revision-aware
  shadow evaluation detect calibration drift. Do not publish client-computed
  percentile gauges that cannot be aggregated correctly.

Expose metrics for model load duration, inference latency, queue depth/rejection,
provider failover rotation count, classifier decision distribution, classifier
circuit transitions/half-open probes, and best-effort resident memory. Expose exact
model revisions and active index fingerprints through a protected diagnostic/health
response, not metric labels.

## 13. Verification and acceptance criteria

| Area | Required evidence |
| --- | --- |
| Correctness | Per-partition 99.5% tolerance pass rate, 100% canonical fixtures, retrieval-quality bounds, masked pooling, normalization, truncation, Unicode, multi-window aggregation, batching |
| Lifecycle | Repeated load/infer/unload and application restart without leaked handles or growing native memory |
| Concurrency | Parallel inference, queue rejection vs. lease timeout, deadline propagation, bounded shutdown drain, idle-only deterministic LRU eviction, operator unload conflict behavior |
| Failover | Local ONNX failure rotates only to a golden-verified compatible embedding space; incompatible/all-slot failure selects non-vector retrieval and emits a privacy-safe audit event |
| Index safety | Fingerprint inventory across every namespace; matching SQLite/PostgreSQL schema evolution; missing/mismatch disables only vectors; explicit dev reset/rebuild on all backends; filesystem restart reconstruction regenerates vectors |
| Security | Checksum/signature failure, traversal rejection, oversized input block, sensitive-log scan, prompt-injection robustness |
| Guard safety | Post-policy ML sees only provisional allows; cannot replace deterministic block/HITL/dedup; ML approval uses the existing `ApprovalFrame` path; unavailable ML yields `ClassifierResult.NoOpinion` |
| Answer retry | Retry only before any tool boundary; never re-enters tool selection or repeats side effects; successful-tool FINISH ordering remains intact |
| Performance | Benchmark suite measuring steady-state latency, cold-start, throughput, queueing, and best-effort RSS on JVM and, when support is claimed, the native binary |
| Native | Supported-platform smoke test using the packaged real model/tokenizer and the Phase 0 numeric pass-rate contract |

Minimum repository checks after implementation include focused module tests, affected core/RAG guard and provider tests, Java formatting/import repair (`python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`), a clean dependency-module install, documentation verification (`python3 ./scripts/verify_docs.py`), and the repository's native build when native support is claimed.

## 14. Consequences and alternatives

### Benefits

- Local and offline embeddings without a Python service.
- Lower latency for local recall and micro-routing.
- Reusable, governed inference substrate for evaluated classifiers.
- Explicit model/index provenance improves reproducibility beyond provider-name-only configuration.

### Future model expansion candidates

The pinned MPNet development bundle is the first real embedding bundle, not an
exclusive model choice. Future adoption remains opt-in and separately evaluated:

- **Fast English embeddings**: `BAAI/bge-small-en-v1.5` and
  `sentence-transformers/all-MiniLM-L6-v2` for lower CPU/memory budgets.
- **Multilingual embeddings**: `intfloat/multilingual-e5-small` or
  `intfloat/multilingual-e5-base` when cross-language retrieval is a product need.
- **Higher-quality English retrieval**: `BAAI/bge-base-en-v1.5` or
  `BAAI/bge-large-en-v1.5`, subject to measured latency and memory budgets.
- **Rerankers**: compact MiniLM or BGE cross-encoders as a separately bounded,
  post-retrieval stage; they are not interchangeable with embedding providers.
- **Classifiers**: compact MiniLM-family models for Phase 2 intent shadowing,
  semantic tool-risk signals, or answer-quality scoring only after each classifier's
  independent dataset, calibration, and safety gates pass.
- **Privacy-oriented NLP**: local NER/PII detection only if a concrete privacy flow
  requires it and it receives its own false-positive/false-negative budget.

Every additional model requires a pinned installer, immutable manifest, license and
provenance review, golden corpus, supported-platform smoke tests, and bounded resource
budget. Each embedding model also receives its own `EmbeddingSpaceDescriptor`; different
space fingerprints may never query or populate the same vector index. A model-space
change therefore follows the explicit development reset/rebuild policy in Section 7.

### Costs and risks

- Native libraries and model weights increase distribution size, memory use, startup time, platform matrix, and vulnerability surface.
- Model quality can drift and classifiers can create unsafe false confidence if uncalibrated.
- JNI/native-image behavior may make JVM-only support the practical first release.

### Alternatives considered

- **Remote embedding APIs**: smallest local footprint, but add network, privacy, cost, and availability dependencies.
- **Ollama/local HTTP service**: isolates native crashes and model memory from Amsha, but adds another process dependency.
- **DJL ONNX Runtime Engine (`ai.djl.onnx:onnxruntime-engine`)**: alternative Java wrapper over ORT, evaluated during Phase 0 against direct `com.microsoft.onnxruntime:onnxruntime`.
- **Pure-Java inference engine**: improves native-image fit, but has a smaller ecosystem.
- **Keyword/rule-only classification**: deterministic and cheap; remains the authoritative safety layer regardless.

## 15. Go/no-go checklist

Phase 1 may become generally available only when:

- the compatibility, tokenizer, and license gates pass;
- model installation is explicit, verified via signatures/checksums, and rollback-safe;
- every configured embedding slot advertises an immutable space descriptor, and
  incompatible slots are rejected for vector failover;
- embedding correctness and fingerprint schema/reset behavior pass contract/parity tests
  across SQLite, PostgreSQL, and filesystem vector namespaces;
- overload cannot starve agent/tool execution;
- no sensitive inference input appears in logs or telemetry;
- JVM packaging works on all supported JVM platforms;
- native support either passes its full matrix or is cleanly excluded;
- default-provider and default-enabled changes receive a separate measured decision.

Phase 3 classifier activation may proceed only when:

- the Phase 2.5 core SPIs have landed and their ordering/composition tests pass with
  no ONNX implementation present;
- the specific classifier's versioned dataset, calibration, false-positive and
  false-negative budgets, model card, and rollback criteria are approved;
- shadow-mode evidence meets those budgets;
- its restart-free kill switch and audit path are verified;
- tool-risk and answer-quality integrations pass the guard-ordering and FINISH-boundary
  acceptance tests in Section 13.
