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
