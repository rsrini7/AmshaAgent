package com.amsha.localml.onnx;

/** Shared identifiers and validation for locally installed ONNX model bundles. */
public final class OnnxModelBundleIds {

    public static final String WHISPER = "whisper-tiny-en-v1";
    public static final String MPNET = "all-mpnet-base-v2-e8c3b32";
    public static final String RERANKER = "bge-reranker-v1";
    public static final String MANIFEST_FILE_NAME = "manifest.json";

    public static final String WHISPER_HINT = "whisper";
    public static final String MPNET_HINT = "mpnet";

    private static final String BUNDLE_ID_PATTERN = "[a-zA-Z0-9][a-zA-Z0-9._-]*";

    private OnnxModelBundleIds() {}

    /** Returns whether an ID can safely name a bundle directory. */
    public static boolean isValid(String bundleId) {
        return bundleId != null && bundleId.matches(BUNDLE_ID_PATTERN);
    }
}
