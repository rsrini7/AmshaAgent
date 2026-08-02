package com.amsha.localml.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.util.Objects;

/** Opens and closes the native ORT session and tokenizer pair for a verified bundle. */
final class DefaultOnnxSessionHandleFactory implements OnnxSessionHandleFactory {
    private final OnnxTokenizerFactory tokenizerFactory;

    DefaultOnnxSessionHandleFactory(OnnxTokenizerFactory tokenizerFactory) {
        this.tokenizerFactory = Objects.requireNonNull(tokenizerFactory, "tokenizerFactory");
    }

    @Override
    public OnnxSessionHandle open(OrtEnvironment environment, VerifiedOnnxModelBundle bundle) throws Exception {
        var embeddingSpec = bundle.manifest().embeddingSpec();
        var classifierSpec = bundle.manifest().classifierSpec();
        int maximumLength = embeddingSpec
                .map(OnnxEmbeddingBundleSpec::maxSequenceLength)
                .or(() -> classifierSpec.map(OnnxClassifierBundleSpec::maxSequenceLength))
                .orElse(0);
        var tokenizer = maximumLength > 0
                ? tokenizerFactory.load(bundle.tokenizerPath(), maximumLength)
                : tokenizerFactory.load(bundle.tokenizerPath());
        try {
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                // Keep native-image execution deliberately conservative until cross-platform
                // execution matrix proves wider ORT threading configuration safe.
                options.setIntraOpNumThreads(1);
                options.setInterOpNumThreads(1);
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                return new OrtSessionHandle(
                        environment.createSession(bundle.modelPath().toString(), options), tokenizer);
            }
        } catch (Exception | LinkageError e) {
            tokenizer.close();
            throw e;
        }
    }

    private record OrtSessionHandle(OrtSession session, HuggingFaceTokenizer tokenizer) implements OnnxSessionHandle {
        @Override
        public void close() throws Exception {
            try {
                session.close();
            } finally {
                tokenizer.close();
            }
        }
    }
}
