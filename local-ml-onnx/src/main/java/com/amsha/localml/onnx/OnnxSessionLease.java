package com.amsha.localml.onnx;

import ai.onnxruntime.OrtSession;

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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) owner.release(bundleSession);
    }
}
