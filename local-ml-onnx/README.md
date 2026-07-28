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
| amsha.embedding.onnx.model | AMSHA_EMBEDDING_ONNX_MODEL | _(empty)_ | Explicit verified local embedding bundle ID; required only when `onnx` is selected as a provider slot. |
| amsha.embedding.onnx.timeout-seconds | AMSHA_EMBEDDING_ONNX_TIMEOUT_SECONDS | 5 | Maximum synchronous wait for one ONNX embedding request. |
| amsha.embedding.onnx.space-id | AMSHA_EMBEDDING_ONNX_SPACE_ID | _(manifest)_ | Optional assertion that must match the verified bundle embedding space. |
| amsha.embedding.onnx.space-revision | AMSHA_EMBEDDING_ONNX_SPACE_REVISION | _(manifest)_ | Optional assertion that must match the verified bundle embedding-space revision. |

`OnnxModelBundleRegistry` loads only an explicitly selected bundle ID below the
configured root. It never downloads assets; each bundle must contain a regular
`manifest.json`, `model.onnx`, and tokenizer file with matching size and SHA-256
values. A tokenizer binding is still gated on Phase 0 validation.

For a reproducible development bundle, run `mise run models:install-mpnet`. It
downloads the Apache-2.0 `sentence-transformers/all-mpnet-base-v2` revision pinned by
the task, verifies its fixed SHA-256 and sizes, and writes the matching local manifest.
The resulting files remain under gitignored `data/models/`.
Run `./mvnw -pl local-ml-onnx -Dtest=OnnxEmbeddingModelSmokeTest test` after
installation to execute the JVM smoke test against the real bundle.

## Tokenizer spike

The Phase 0 JVM candidate is `ai.djl.huggingface:tokenizers` 0.33.0 (Apache-2.0).
It bundles CPU JNI libraries for Linux x86-64, Linux arm64, and macOS arm64 and reads
standard Hugging Face WordPiece, BPE, and Unigram tokenizer JSON files. `OnnxTokenizerFactory`
forces DJL offline mode and tracking opt-out before loading a local tokenizer, and
rejects DJL native-library/flavour overrides, so it cannot download a model or JNI
library at request time. Native-image and full supported-platform validation remain
Phase 0 gates.
