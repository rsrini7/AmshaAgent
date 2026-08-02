package com.amsha.localml.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensorLike;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One in-flight right to use a cached ONNX session. Callers must close it exactly once. */
public final class OnnxSessionLease implements AutoCloseable {

    private final OnnxSessionLeaseManager owner;
    private final OnnxSessionLeaseManager.BundleSession bundleSession;
    private final OnnxSessionHandle handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    OnnxSessionLease(
            OnnxSessionLeaseManager owner,
            OnnxSessionLeaseManager.BundleSession bundleSession,
            OnnxSessionHandle handle) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.bundleSession = Objects.requireNonNull(bundleSession, "bundleSession");
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    /** Returns the leased session. The lease must remain open for the complete {@code run()} call. */
    public OrtSession session() {
        return handle.session();
    }

    HuggingFaceTokenizer tokenizer() {
        return handle.tokenizer();
    }

    /** Runs one cancellable graph invocation while this lease owns the session. */
    public OrtSession.Result run(Map<String, ? extends OnnxTensorLike> inputs) throws OrtException {
        return run(inputs, null);
    }

    /** Runs one graph invocation and asks ORT to terminate it when the request deadline arrives. */
    public OrtSession.Result run(Map<String, ? extends OnnxTensorLike> inputs, Instant deadline) throws OrtException {
        if (closed.get()) throw new IllegalStateException("ONNX session lease is closed");
        OrtSession.RunOptions runOptions = owner.beginRun(bundleSession, deadline);
        try {
            return handle.session().run(inputs, runOptions);
        } finally {
            owner.endRun(bundleSession, runOptions);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) owner.release(bundleSession);
    }
}
