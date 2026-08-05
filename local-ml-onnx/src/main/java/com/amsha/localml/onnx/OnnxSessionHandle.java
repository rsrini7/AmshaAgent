package com.amsha.localml.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtSession;

/** A session owned by the local-ML lifecycle manager. */
interface OnnxSessionHandle extends AutoCloseable {

    OrtSession session();

    HuggingFaceTokenizer tokenizer();

    @Override
    void close() throws Exception;
}
