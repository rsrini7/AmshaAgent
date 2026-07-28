package com.amsha.localml.onnx;

import ai.onnxruntime.OrtSession;

/** A session owned by the local-ML lifecycle manager. */
interface OnnxSessionHandle extends AutoCloseable {

    OrtSession session();

    @Override
    void close() throws Exception;
}
