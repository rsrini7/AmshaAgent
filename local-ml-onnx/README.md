# Local ML ONNX

Optional in-process ONNX Runtime integration for Amsha Agent.

## Configuration

| Property | Environment variable | Default | Purpose |
| --- | --- | --- | --- |
| amsha.onnx.enabled | AMSHA_ONNX_ENABLED | false | Enables the ONNX Runtime JVM compatibility probe. |
| amsha.onnx.model-root | AMSHA_ONNX_MODEL_ROOT | data/models | Root for verified local model bundles. |
| amsha.onnx.max-model-file-bytes | AMSHA_ONNX_MAX_MODEL_FILE_BYTES | 1073741824 | Maximum size of each verified bundle file, in bytes. |
| amsha.onnx.inference-threads | AMSHA_ONNX_INFERENCE_THREADS | 2 | Fixed number of CPU ONNX inference workers. |
| amsha.onnx.queue-capacity | AMSHA_ONNX_QUEUE_CAPACITY | 64 | Maximum queued ONNX inference requests before rejection. |
| amsha.onnx.shutdown-grace | AMSHA_ONNX_SHUTDOWN_GRACE | PT10S | Maximum time to drain in-flight ONNX session leases during unload or shutdown. |
| amsha.onnx.max-input-bytes | AMSHA_ONNX_MAX_INPUT_BYTES | 262144 | UTF-8 byte limit for one original input segment before tokenization. |
| amsha.onnx.max-batch-input-bytes | AMSHA_ONNX_MAX_BATCH_INPUT_BYTES | 1048576 | UTF-8 byte limit across original input segments in one embedding batch. |
| amsha.onnx.max-batch-size | AMSHA_ONNX_MAX_BATCH_SIZE | 32 | Maximum original input segments accepted in one embedding batch. |
| amsha.onnx.warmup.enabled | AMSHA_ONNX_WARMUP_ENABLED | true | Runs deterministic manifest-declared probes before exposing an ONNX slot. |
| amsha.onnx.warmup.max-probes | AMSHA_ONNX_WARMUP_MAX_PROBES | 5 | Maximum declared warm-up probes run per bundle. |
| amsha.onnx.warmup.timeout | AMSHA_ONNX_WARMUP_TIMEOUT | PT3S | Total deadline for one bundle warm-up. |
| amsha.classifier.shadow.threads | AMSHA_CLASSIFIER_SHADOW_THREADS | 2 | Dedicated workspace-aware classifier observation workers, isolated from agent work pools. |
| amsha.classifier.shadow.queue-capacity | AMSHA_CLASSIFIER_SHADOW_QUEUE_CAPACITY | 64 | Maximum queued shadow observations before `NoOpinion(OVERLOAD_REJECTED)`. |
| amsha.classifier.shadow.shutdown-grace | AMSHA_CLASSIFIER_SHADOW_SHUTDOWN_GRACE | PT5S | Maximum time to drain accepted shadow observations during shutdown. |
| amsha.onnx.classifier.circuit.failure-threshold | AMSHA_ONNX_CLASSIFIER_CIRCUIT_FAILURE_THRESHOLD | 3 | Consecutive model load, timeout, or inference failures before a standalone classifier circuit opens. Queue rejection does not count. |
| amsha.onnx.classifier.circuit.cooldown-seconds | AMSHA_ONNX_CLASSIFIER_CIRCUIT_COOLDOWN_SECONDS | 30 | Delay before one half-open standalone-classifier probe is admitted. |
| amsha.onnx.classifier.intent.enabled | AMSHA_ONNX_CLASSIFIER_INTENT_ENABLED | false | Enables Phase 2 intent inference in shadow mode only; it cannot select the active route. |
| amsha.onnx.classifier.intent.model | AMSHA_ONNX_CLASSIFIER_INTENT_MODEL | _(empty)_ | Explicit verified local classifier bundle ID. An empty value produces `NoOpinion(MISSING_MODEL)`. |
| amsha.onnx.classifier.intent.timeout-seconds | AMSHA_ONNX_CLASSIFIER_INTENT_TIMEOUT_SECONDS | 2 | Total wait deadline for one shadow intent-classifier request. |
| amsha.onnx.classifier.tool-risk.enabled | AMSHA_ONNX_CLASSIFIER_TOOL_RISK_ENABLED | false | Enables Phase 2 tool-risk inference only after the deterministic guard provisionally allows a call; it cannot block or request approval. |
| amsha.onnx.classifier.tool-risk.model | AMSHA_ONNX_CLASSIFIER_TOOL_RISK_MODEL | _(empty)_ | Explicit verified local tool-risk bundle ID. |
| amsha.onnx.classifier.tool-risk.timeout-seconds | AMSHA_ONNX_CLASSIFIER_TOOL_RISK_TIMEOUT_SECONDS | 2 | Total wait deadline for one tool-risk shadow request. |
| amsha.onnx.classifier.answer-quality.enabled | AMSHA_ONNX_CLASSIFIER_ANSWER_QUALITY_ENABLED | false | Enables Phase 2 scoring only after the authoritative answer guard accepts; it cannot retry or alter the answer. |
| amsha.onnx.classifier.answer-quality.model | AMSHA_ONNX_CLASSIFIER_ANSWER_QUALITY_MODEL | _(empty)_ | Explicit verified local answer-quality bundle ID. |
| amsha.onnx.classifier.answer-quality.timeout-seconds | AMSHA_ONNX_CLASSIFIER_ANSWER_QUALITY_TIMEOUT_SECONDS | 2 | Total wait deadline for one answer-quality shadow request. |
| amsha.embedding.onnx.model | AMSHA_EMBEDDING_ONNX_MODEL | _(empty)_ | Explicit verified local embedding bundle ID; required only when `onnx` is selected as a provider slot. |
| amsha.embedding.onnx.timeout-seconds | AMSHA_EMBEDDING_ONNX_TIMEOUT_SECONDS | 5 | Maximum synchronous wait for one ONNX embedding request. |
| amsha.embedding.onnx.space-id | AMSHA_EMBEDDING_ONNX_SPACE_ID | _(manifest)_ | Optional assertion that must match the verified bundle embedding space. |
| amsha.embedding.onnx.space-revision | AMSHA_EMBEDDING_ONNX_SPACE_REVISION | _(manifest)_ | Optional assertion that must match the verified bundle embedding-space revision. |

`OnnxModelBundleRegistry` loads only an explicitly selected bundle ID below the
configured root. It never downloads assets; each bundle must contain a regular
`manifest.json`, `model.onnx`, and tokenizer file with matching size and SHA-256
values. A tokenizer binding is still gated on Phase 0 validation.

Classifier bundles use the same verified local-file and tokenizer controls but declare
a mutually exclusive `classifier` contract instead of `embedding`. The contract pins
the graph input/output names, maximum sequence length, ordered labels, score transform,
minimum confidence, rule identity, and deterministic warm-up probes. Intent compares
against the unchanged authoritative route. Tool-risk observes only calls already
provisionally allowed by every deterministic guard. Answer-quality observes only final
candidates accepted by the authoritative answer guard and records whether a tool was
invoked, but never retries across that boundary. All three adapters asynchronously emit
bounded match/mismatch/no-opinion metrics and never return a behavioral decision. Raw
input, parameters, answers, labels, rule IDs, model revisions, and exception details are
not metric tags or logs. No classifier model is bundled or selected by default.

The Phase 2.5 core classifier SPI gate is complete. Core orders optional implementations
by priority and binary class name, preserves every deterministic block, deduplication,
validation, and HITL decision, and permits only monotonic tool-risk upgrades. Its
answer-quality path shares one retry budget and rejects retry after a tool boundary.
This module intentionally provides no active `PostPolicyToolRiskInterceptor` or
`AnswerQualityInterceptor`; its existing classifier adapters remain shadow-only until
the corresponding Phase 2 evidence and Phase 3 activation gates are approved.

The [Phase 2 classifier preparation and resumption runbook](classifier-training/README.md)
documents the current pause point, human-review requirements, Git retention boundary,
and exact `mise` command sequence. All operations enter through
`scripts/classifier_training.sh` and the `classifier:*` `mise` tasks. Run
`mise run classifier:validate-seeds` to validate the pending repository-contract review
packets and `mise run classifier:install-base` to install the immutable, SHA-256-pinned
Apache-2.0 `all-MiniLM-L6-v2` training source. Train, evaluate, and package tasks fail
before loading ML dependencies unless the selected dataset is human-approved and meets
the minimum split sizes. Packaging also requires approved error budgets and passing
held-out evidence. Insufficient classifier data does not block later non-activating
implementation phases, but it remains a hard gate for model promotion and Phase 3
behavioral activation.

For a reproducible development bundle, run `mise run models:install-mpnet`. It
downloads the Apache-2.0 `sentence-transformers/all-mpnet-base-v2` revision pinned by
the task, verifies its fixed SHA-256 and sizes, and writes the matching local manifest.
The resulting files remain under gitignored `data/models/`.
Run `./mvnw -pl local-ml-onnx -Dtest=OnnxEmbeddingModelSmokeTest test` after
installation to execute the JVM smoke test against the real bundle.

For repeatable authenticated OMLX, running-server, and isolated native checks, use the
[local runtime smoke runner](../docs/ONNX_LOCAL_ML_GUIDE.md#reusable-local-runtime-smoke-runner).
All entry points go through `scripts/local_runtime_smoke.sh` and
`mise run smoke:local-runtime`; credentials remain in the ignored root `.env`, and the
native workflow forces `generic-openai` plus ONNX without selecting Ollama.

## Tokenizer spike

The Phase 0 JVM candidate is `ai.djl.huggingface:tokenizers` 0.33.0 (Apache-2.0).
It bundles CPU JNI libraries for Linux x86-64, Linux arm64, and macOS arm64 and reads
standard Hugging Face WordPiece, BPE, and Unigram tokenizer JSON files. `OnnxTokenizerFactory`
forces DJL offline mode and tracking opt-out before loading a local tokenizer, and
rejects DJL native-library/flavour overrides, so it cannot download a model or JNI
library at request time. Native-image and full supported-platform validation remain
Phase 0 gates.
