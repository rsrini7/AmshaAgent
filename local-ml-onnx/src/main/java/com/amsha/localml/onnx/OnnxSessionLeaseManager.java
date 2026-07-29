package com.amsha.localml.onnx;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Owns cached sessions and makes close-versus-inference races impossible through per-bundle
 * leases. It deliberately has no scheduler or eviction policy; those layers must call this
 * manager rather than closing an {@link OrtSession} directly.
 */
@ApplicationScoped
public class OnnxSessionLeaseManager {

    private static final Logger log = Logger.getLogger(OnnxSessionLeaseManager.class);

    private final Supplier<Optional<OrtEnvironment>> environmentSupplier;
    private final OnnxSessionHandleFactory sessionFactory;
    private final Duration shutdownGrace;
    private final Map<String, BundleSession> sessions = new HashMap<>();

    @Inject
    public OnnxSessionLeaseManager(
            OnnxRuntimeManager runtimeManager,
            OnnxTokenizerFactory tokenizerFactory,
            @ConfigProperty(name = "amsha.onnx.shutdown-grace", defaultValue = "PT10S") Duration shutdownGrace) {
        this(runtimeManager::environment, new OrtSessionHandleFactoryImpl(tokenizerFactory), shutdownGrace);
    }

    OnnxSessionLeaseManager(
            Supplier<Optional<OrtEnvironment>> environmentSupplier,
            OnnxSessionHandleFactory sessionFactory,
            Duration shutdownGrace) {
        this.environmentSupplier = Objects.requireNonNull(environmentSupplier, "environmentSupplier");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.shutdownGrace = requireNonNegative(shutdownGrace, "shutdownGrace");
    }

    /**
     * Acquires a bundle session before the caller's absolute request deadline. A rejected lease
     * never runs inference and a returned lease keeps its session open until it is closed.
     */
    public OnnxSessionLease acquire(VerifiedOnnxModelBundle bundle, Instant deadline)
            throws OnnxSessionAcquireException {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(deadline, "deadline");
        ensureBeforeDeadline(deadline);
        BundleSession bundleSession = sessionFor(bundle.manifest().bundleId());
        synchronized (bundleSession) {
            if (bundleSession.closing) {
                throw new OnnxSessionAcquireException(
                        OnnxSessionAcquireException.Reason.BUNDLE_CLOSING, "ONNX bundle is closing");
            }
            OnnxSessionHandle handle = bundleSession.handle;
            if (handle == null) handle = open(bundle, deadline, bundleSession);
            ensureBeforeDeadline(deadline);
            bundleSession.leaseCount++;
            return new OnnxSessionLease(this, bundleSession, handle);
        }
    }

    /**
     * Rejects new leases, then closes an idle session. If the grace deadline expires first, the
     * session remains closing and active callers retain ownership until they return.
     */
    public UnloadResult unload(String bundleId, Duration grace) {
        Objects.requireNonNull(bundleId, "bundleId");
        Duration boundedGrace = requireNonNegative(grace, "grace");
        BundleSession bundleSession;
        synchronized (sessions) {
            bundleSession = sessions.get(bundleId);
        }
        if (bundleSession == null) return UnloadResult.NOT_LOADED;
        synchronized (bundleSession) {
            bundleSession.closing = true;
            Instant deadline = Instant.now().plus(boundedGrace);
            while (bundleSession.leaseCount > 0 && waitForLease(bundleSession, deadline)) {
                // Wait until the bounded grace deadline or the final lease return.
            }
            if (bundleSession.leaseCount > 0) return UnloadResult.DRAINING;
            closeAndRemove(bundleId, bundleSession);
            return UnloadResult.UNLOADED;
        }
    }

    /** Performs bounded, cooperative shutdown; lingering native handles are left to process exit. */
    @PreDestroy
    void shutdown() {
        List<Map.Entry<String, BundleSession>> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions.entrySet());
        }
        Instant deadline = Instant.now().plus(shutdownGrace);
        for (Map.Entry<String, BundleSession> entry : snapshot) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            UnloadResult result = unload(entry.getKey(), remaining.isNegative() ? Duration.ZERO : remaining);
            if (result == UnloadResult.DRAINING) {
                log.warnf("ONNX bundle still has in-flight leases at shutdown: %s", entry.getKey());
            }
        }
    }

    void release(BundleSession bundleSession) {
        synchronized (bundleSession) {
            if (bundleSession.leaseCount <= 0) {
                throw new IllegalStateException("ONNX session lease released more than once");
            }
            bundleSession.leaseCount--;
            if (bundleSession.closing && bundleSession.leaseCount == 0) {
                closeAndRemove(bundleSession.bundleId, bundleSession);
            }
            bundleSession.notifyAll();
        }
    }

    private BundleSession sessionFor(String bundleId) {
        synchronized (sessions) {
            return sessions.computeIfAbsent(bundleId, BundleSession::new);
        }
    }

    private OnnxSessionHandle open(VerifiedOnnxModelBundle bundle, Instant deadline, BundleSession bundleSession)
            throws OnnxSessionAcquireException {
        Optional<OrtEnvironment> environment = environmentSupplier.get();
        if (environment.isEmpty()) {
            throw new OnnxSessionAcquireException(
                    OnnxSessionAcquireException.Reason.RUNTIME_UNAVAILABLE, "ONNX Runtime is unavailable");
        }
        try {
            OnnxSessionHandle handle = sessionFactory.open(environment.get(), bundle);
            bundleSession.handle = Objects.requireNonNull(handle, "sessionFactory result");
            ensureBeforeDeadline(deadline);
            return handle;
        } catch (OnnxSessionAcquireException e) {
            closeQuietly(bundleSession.handle);
            bundleSession.handle = null;
            throw e;
        } catch (Exception | LinkageError e) {
            closeQuietly(bundleSession.handle);
            bundleSession.handle = null;
            throw new OnnxSessionAcquireException(
                    OnnxSessionAcquireException.Reason.SESSION_LOAD_FAILED,
                    "Unable to load verified ONNX model session",
                    e);
        }
    }

    private void closeAndRemove(String bundleId, BundleSession bundleSession) {
        closeQuietly(bundleSession.handle);
        bundleSession.handle = null;
        synchronized (sessions) {
            sessions.remove(bundleId, bundleSession);
        }
    }

    private static void ensureBeforeDeadline(Instant deadline) throws OnnxSessionAcquireException {
        if (!Instant.now().isBefore(deadline)) {
            throw new OnnxSessionAcquireException(
                    OnnxSessionAcquireException.Reason.DEADLINE_EXCEEDED, "ONNX session lease deadline exceeded");
        }
    }

    private static boolean waitForLease(BundleSession bundleSession, Instant deadline) {
        long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
        if (remainingMillis <= 0) return false;
        try {
            bundleSession.wait(remainingMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private static void closeQuietly(OnnxSessionHandle handle) {
        if (handle == null) return;
        try {
            handle.close();
        } catch (Exception | LinkageError e) {
            log.warnf("ONNX session cleanup failed: %s", e.getMessage());
        }
    }

    public enum UnloadResult {
        NOT_LOADED,
        DRAINING,
        UNLOADED
    }

    static final class BundleSession {
        private final String bundleId;
        private OnnxSessionHandle handle;
        private int leaseCount;
        private boolean closing;

        BundleSession(String bundleId) {
            this.bundleId = bundleId;
        }
    }

    private static final class OrtSessionHandleFactoryImpl implements OnnxSessionHandleFactory {
        private final OnnxTokenizerFactory tokenizerFactory;

        OrtSessionHandleFactoryImpl(OnnxTokenizerFactory tokenizerFactory) {
            this.tokenizerFactory = Objects.requireNonNull(tokenizerFactory, "tokenizerFactory");
        }

        @Override
        public OnnxSessionHandle open(OrtEnvironment environment, VerifiedOnnxModelBundle bundle) throws Exception {
            var embeddingSpec = bundle.manifest().embeddingSpec();
            var classifierSpec = bundle.manifest().classifierSpec();
            int maximumLength = embeddingSpec
                    .map(OnnxEmbeddingBundleSpec::maxSequenceLength)
                    .or(() -> classifierSpec.map(OnnxClassifierBundleSpec::maxSequenceLength))
                    .orElse(0);
            var tokenizer = maximumLength > 0
                    ? tokenizerFactory.load(bundle.tokenizerPath(), maximumLength)
                    : tokenizerFactory.load(bundle.tokenizerPath());
            try {
                try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                    // Keep native-image execution deliberately conservative until the cross-platform
                    // execution matrix proves a wider ORT threading configuration is safe.
                    options.setIntraOpNumThreads(1);
                    options.setInterOpNumThreads(1);
                    options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                    return new OrtSessionHandle(
                            environment.createSession(bundle.modelPath().toString(), options), tokenizer);
                }
            } catch (Exception | LinkageError e) {
                tokenizer.close();
                throw e;
            }
        }
    }

    private record OrtSessionHandle(OrtSession session, ai.djl.huggingface.tokenizers.HuggingFaceTokenizer tokenizer)
            implements OnnxSessionHandle {
        @Override
        public void close() throws Exception {
            try {
                session.close();
            } finally {
                tokenizer.close();
            }
        }
    }
}
