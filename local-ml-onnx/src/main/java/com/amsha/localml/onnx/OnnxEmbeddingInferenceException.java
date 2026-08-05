package com.amsha.localml.onnx;

/** A checked, payload-free failure while executing a verified ONNX embedding bundle. */
public class OnnxEmbeddingInferenceException extends Exception {

    OnnxEmbeddingInferenceException(String message) {
        super(message);
    }

    OnnxEmbeddingInferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
