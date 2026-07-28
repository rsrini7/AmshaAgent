# Local ML ONNX

Optional in-process ONNX Runtime integration for Amsha Agent.

## Configuration

| Property | Environment variable | Default | Purpose |
| --- | --- | --- | --- |
| amsha.onnx.enabled | AMSHA_ONNX_ENABLED | false | Enables the ONNX Runtime JVM compatibility probe. |
| amsha.onnx.model-root | AMSHA_ONNX_MODEL_ROOT | data/models | Root for verified local model bundles. |
| amsha.onnx.max-model-file-bytes | AMSHA_ONNX_MAX_MODEL_FILE_BYTES | 1073741824 | Maximum size of each verified bundle file, in bytes. |

`OnnxModelBundleRegistry` loads only an explicitly selected bundle ID below the
configured root. It never downloads assets; each bundle must contain a regular
`manifest.json`, `model.onnx`, and tokenizer file with matching size and SHA-256
values. A tokenizer binding is still gated on Phase 0 validation.

## Tokenizer spike

The Phase 0 JVM candidate is `ai.djl.huggingface:tokenizers` 0.33.0 (Apache-2.0).
It bundles CPU JNI libraries for Linux x86-64, Linux arm64, and macOS arm64 and reads
standard Hugging Face WordPiece, BPE, and Unigram tokenizer JSON files. `OnnxTokenizerFactory`
forces DJL offline mode and tracking opt-out before loading a local tokenizer, and
rejects DJL native-library/flavour overrides, so it cannot download a model or JNI
library at request time. Native-image and full supported-platform validation remain
Phase 0 gates.
