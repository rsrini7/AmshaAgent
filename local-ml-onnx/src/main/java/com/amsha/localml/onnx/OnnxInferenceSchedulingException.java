package com.amsha.localml.onnx;

/** A checked, non-content-bearing reason that inference work was not accepted or completed. */
public class OnnxInferenceSchedulingException extends Exception {

    public enum Reason {
        OVERLOAD_REJECTED,
        TIMEOUT,
        SCHEDULER_STOPPED
    }

    private final Reason reason;

    OnnxInferenceSchedulingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
