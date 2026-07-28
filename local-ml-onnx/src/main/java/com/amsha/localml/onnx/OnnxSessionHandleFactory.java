package com.amsha.localml.onnx;

import ai.onnxruntime.OrtEnvironment;

/** Opens a session only for a verified local model bundle. */
@FunctionalInterface
interface OnnxSessionHandleFactory {

    OnnxSessionHandle open(OrtEnvironment environment, VerifiedOnnxModelBundle bundle) throws Exception;
}
