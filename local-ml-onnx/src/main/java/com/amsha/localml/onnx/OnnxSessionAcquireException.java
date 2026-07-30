package com.amsha.localml.onnx;

/** A checked, privacy-safe reason why a verified bundle session could not be leased. */
public class OnnxSessionAcquireException extends Exception {

    public enum Reason {
        RUNTIME_UNAVAILABLE,
        DEADLINE_EXCEEDED,
        ADMISSION_REJECTED,
        BUNDLE_CLOSING,
        SESSION_LOAD_FAILED
    }

    private final Reason reason;

    OnnxSessionAcquireException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    OnnxSessionAcquireException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
