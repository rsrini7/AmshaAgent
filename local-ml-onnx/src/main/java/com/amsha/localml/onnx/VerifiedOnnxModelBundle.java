package com.amsha.localml.onnx;

import java.nio.file.Path;

/** Verified, canonical paths for a model and tokenizer bundle. */
public record VerifiedOnnxModelBundle(OnnxModelBundleManifest manifest, Path modelPath, Path tokenizerPath) {}
