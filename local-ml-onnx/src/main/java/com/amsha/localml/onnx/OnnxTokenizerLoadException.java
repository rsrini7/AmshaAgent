package com.amsha.localml.onnx;

import java.io.IOException;

/** Indicates that a verified local tokenizer cannot be safely initialized. */
public class OnnxTokenizerLoadException extends IOException {

    public OnnxTokenizerLoadException(String message) {
        super(message);
    }

    public OnnxTokenizerLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
