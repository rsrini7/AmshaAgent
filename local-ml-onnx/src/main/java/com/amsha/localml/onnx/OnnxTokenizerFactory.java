package com.amsha.localml.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Creates local Hugging Face tokenizers without permitting DJL network access or JNI downloads. */
@ApplicationScoped
public class OnnxTokenizerFactory {

    private static final String DJL_OFFLINE_PROPERTY = "ai.djl.offline";
    private static final String OPT_OUT_TRACKING_PROPERTY = "OPT_OUT_TRACKING";

    /**
     * Loads a tokenizer only from a verified local bundle file. The caller owns and must close the
     * returned native tokenizer.
     */
    public HuggingFaceTokenizer load(Path tokenizerPath) throws OnnxTokenizerLoadException {
        return load(tokenizerPath, null);
    }

    /**
     * Loads a tokenizer with truncation configured before encoding so post-processor special tokens
     * remain valid at the declared model boundary.
     */
    public HuggingFaceTokenizer load(Path tokenizerPath, int maxSequenceLength) throws OnnxTokenizerLoadException {
        if (maxSequenceLength <= 0) throw new IllegalArgumentException("maxSequenceLength must be positive");
        return load(tokenizerPath, Integer.valueOf(maxSequenceLength));
    }

    private HuggingFaceTokenizer load(Path tokenizerPath, Integer maxSequenceLength) throws OnnxTokenizerLoadException {
        Objects.requireNonNull(tokenizerPath, "tokenizerPath");
        enforceBundledCpuOnlyDjl();
        try {
            if (maxSequenceLength != null) {
                return HuggingFaceTokenizer.builder()
                        .optTokenizerPath(tokenizerPath)
                        .optTruncation(true)
                        .optMaxLength(maxSequenceLength)
                        .build();
            }
            return HuggingFaceTokenizer.newInstance(tokenizerPath);
        } catch (IOException | RuntimeException e) {
            throw new OnnxTokenizerLoadException("Failed to parse verified local tokenizer file", e);
        }
    }

    private static void enforceBundledCpuOnlyDjl() throws OnnxTokenizerLoadException {
        requireOfflineEnvironment();
        rejectNativeLibraryOverride("RUST_LIBRARY_PATH");
        rejectNativeLibraryOverride("RUST_FLAVOR");
        System.setProperty(DJL_OFFLINE_PROPERTY, Boolean.TRUE.toString());
        System.setProperty(OPT_OUT_TRACKING_PROPERTY, Boolean.TRUE.toString());
    }

    private static void requireOfflineEnvironment() throws OnnxTokenizerLoadException {
        String value = System.getenv("DJL_OFFLINE");
        if (value != null && !Boolean.parseBoolean(value)) {
            throw new OnnxTokenizerLoadException("DJL_OFFLINE must be true when it is set");
        }
    }

    private static void rejectNativeLibraryOverride(String name) throws OnnxTokenizerLoadException {
        String value = System.getenv(name);
        if (value == null) value = System.getProperty(name);
        if (value != null && !value.isBlank()) {
            throw new OnnxTokenizerLoadException("Unsafe DJL environment override is set: " + name);
        }
    }
}
