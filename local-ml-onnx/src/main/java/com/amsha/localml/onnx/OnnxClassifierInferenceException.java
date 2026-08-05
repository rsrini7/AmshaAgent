package com.amsha.localml.onnx;

/** A checked, payload-free failure while executing a verified ONNX classifier bundle. */
public class OnnxClassifierInferenceException extends Exception {

    OnnxClassifierInferenceException(String message) {
        super(message);
    }

    OnnxClassifierInferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
