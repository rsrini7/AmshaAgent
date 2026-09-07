package com.amsha.localml.onnx;

import java.nio.file.Path;

/** Verified, canonical paths for a model, tokenizer, and optional transcription bundle. */
public record VerifiedOnnxModelBundle(
        OnnxModelBundleManifest manifest,
        Path modelPath,
        Path tokenizerPath,
        Path decoderPath,
        Path preprocessorConfigPath,
        Path configPath) {

    public VerifiedOnnxModelBundle(OnnxModelBundleManifest manifest, Path modelPath, Path tokenizerPath) {
        this(manifest, modelPath, tokenizerPath, null, null, null);
    }
}
