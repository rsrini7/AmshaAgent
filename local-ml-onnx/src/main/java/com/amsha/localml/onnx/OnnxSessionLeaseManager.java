package com.amsha.localml.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Owns admission-controlled cached sessions and makes close-versus-inference races impossible
 * through per-bundle leases. Idle sessions are evicted in least-recently-used order when a loaded
 * model or conservative resident-memory budget would otherwise be exceeded.
 */
@ApplicationScoped
public class OnnxSessionLeaseManager {

    private static final int DEFAULT_MAX_LOADED_MODELS = 2;
    private static final long DEFAULT_MAX_RESIDENT_MB = 1024;
    private static final long BYTES_PER_MEBIBYTE = 1024L * 1024L;
    private static final Logger log = Logger.getLogger(OnnxSessionLeaseManager.class);

    private final Supplier<Optional<OrtEnvironment>> environmentSupplier;
    private final OnnxSessionHandleFactory sessionFactory;
    private final Duration shutdownGrace;
    private final int maxLoadedModels;
    private final long maxResidentBytes;
    private final Object admissionLock = new Object();
    private final AtomicLong accessSequence = new AtomicLong();
    private final Map<String, BundleSession> sessions = new HashMap<>();
    private boolean shuttingDown;

    @Inject
    public OnnxSessionLeaseManager(
            OnnxRuntimeManager runtimeManager,
            OnnxTokenizerFactory tokenizerFactory,
            @ConfigProperty(name = "amsha.onnx.shutdown-grace", defaultValue = "PT10S") Duration shutdownGrace,
            @ConfigProperty(name = "amsha.onnx.max-loaded-models", defaultValue = "2") int maxLoadedModels,
            @ConfigProperty(name = "amsha.onnx.max-resident-mb", defaultValue = "1024") long maxResidentMb) {
        this(
                runtimeManager::environment,
                new OrtSessionHandleFactoryImpl(tokenizerFactory),
                shutdownGrace,
                maxLoadedModels,
                maxResidentMb);
    }

    OnnxSessionLeaseManager(
            OnnxRuntimeManager runtimeManager, OnnxTokenizerFactory tokenizerFactory, Duration shutdownGrace) {
        this(
                runtimeManager::environment,
                new OrtSessionHandleFactoryImpl(tokenizerFactory),
                shutdownGrace,
                DEFAULT_MAX_LOADED_MODELS,
                DEFAULT_MAX_RESIDENT_MB);
    }

    OnnxSessionLeaseManager(
            Supplier<Optional<OrtEnvironment>> environmentSupplier,
            OnnxSessionHandleFactory sessionFactory,
            Duration shutdownGrace) {
        this(environmentSupplier, sessionFactory, shutdownGrace, DEFAULT_MAX_LOADED_MODELS, DEFAULT_MAX_RESIDENT_MB);
    }

    OnnxSessionLeaseManager(
            Supplier<Optional<OrtEnvironment>> environmentSupplier,
            OnnxSessionHandleFactory sessionFactory,
            Duration shutdownGrace,
            int maxLoadedModels,
            long maxResidentMb) {
        this.environmentSupplier = Objects.requireNonNull(environmentSupplier, "environmentSupplier");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.shutdownGrace = requireNonNegative(shutdownGrace, "shutdownGrace");
        this.maxLoadedModels = requirePositive(maxLoadedModels, "maxLoadedModels");
        this.maxResidentBytes = mebibytesToBytes(maxResidentMb);
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
        BundleSession bundleSession = sessionFor(bundle, deadline);
        synchronized (bundleSession) {
            if (bundleSession.closing) {
                throw new OnnxSessionAcquireException(
                        OnnxSessionAcquireException.Reason.BUNDLE_CLOSING, "ONNX bundle is closing");
            }
            OnnxSessionHandle handle = bundleSession.handle;
            if (handle == null) handle = open(bundle, deadline, bundleSession);
            ensureBeforeDeadline(deadline);
            bundleSession.leaseCount++;
            bundleSession.lastAccessSequence = accessSequence.incrementAndGet();
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
            requestCancellation(bundleSession);
            Instant deadline = Instant.now().plus(boundedGrace);
            while (hasInFlightWork(bundleSession) && waitForLease(bundleSession, deadline)) {
                // Wait until the bounded grace deadline or the final lease return.
            }
            if (hasInFlightWork(bundleSession)) return UnloadResult.DRAINING;
            closeAndRemove(bundleId, bundleSession);
            return UnloadResult.UNLOADED;
        }
    }

    /** Performs bounded, cooperative shutdown; lingering native handles are left to process exit. */
    @PreDestroy
    void shutdown() {
        synchronized (admissionLock) {
            shuttingDown = true;
        }
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
            if (bundleSession.closing && !hasInFlightWork(bundleSession)) {
                closeAndRemove(bundleSession.bundleId, bundleSession);
            }
            bundleSession.notifyAll();
        }
    }

    OrtSession.RunOptions beginRun(BundleSession bundleSession) throws OrtException {
        synchronized (bundleSession) {
            if (bundleSession.closing) {
                throw new IllegalStateException("ONNX bundle is closing");
            }
            OrtSession.RunOptions runOptions = new OrtSession.RunOptions();
            bundleSession.activeRuns.add(runOptions);
            return runOptions;
        }
    }

    void endRun(BundleSession bundleSession, OrtSession.RunOptions runOptions) {
        synchronized (bundleSession) {
            bundleSession.activeRuns.remove(runOptions);
            runOptions.close();
            if (bundleSession.closing && !hasInFlightWork(bundleSession)) {
                closeAndRemove(bundleSession.bundleId, bundleSession);
            }
            bundleSession.notifyAll();
        }
    }

    private BundleSession sessionFor(VerifiedOnnxModelBundle bundle, Instant deadline)
            throws OnnxSessionAcquireException {
        String bundleId = bundle.manifest().bundleId();
        synchronized (admissionLock) {
            if (shuttingDown) {
                throw new OnnxSessionAcquireException(
                        OnnxSessionAcquireException.Reason.BUNDLE_CLOSING, "ONNX Runtime is shutting down");
            }
            BundleSession existing = currentSession(bundleId);
            if (existing != null) return existing;

            long estimatedResidentBytes = estimateResidentBytes(bundle);
            ensureAdmissionCapacity(estimatedResidentBytes, deadline);
            BundleSession created =
                    new BundleSession(bundleId, estimatedResidentBytes, accessSequence.incrementAndGet());
            synchronized (sessions) {
                sessions.put(bundleId, created);
            }
            return created;
        }
    }

    private void ensureAdmissionCapacity(long requiredBytes, Instant deadline) throws OnnxSessionAcquireException {
        if (requiredBytes > maxResidentBytes) {
            throw admissionRejected("ONNX bundle exceeds the configured resident-memory admission budget");
        }
        Set<BundleSession> skipped = new HashSet<>();
        while (true) {
            ensureBeforeDeadline(deadline);
            CapacitySnapshot capacity = capacitySnapshot();
            if (capacity.loadedModels() < maxLoadedModels
                    && capacity.reservedBytes() <= maxResidentBytes - requiredBytes) {
                return;
            }

            BundleSession candidate = leastRecentlyUsedIdleSession(skipped);
            if (candidate == null) {
                throw admissionRejected("ONNX session admission capacity is exhausted");
            }
            synchronized (candidate) {
                if (!isCurrent(candidate)
                        || candidate.closing
                        || candidate.leaseCount > 0
                        || candidate.handle == null) {
                    skipped.add(candidate);
                    continue;
                }
                candidate.closing = true;
                closeAndRemove(candidate.bundleId, candidate);
            }
        }
    }

    private CapacitySnapshot capacitySnapshot() {
        synchronized (sessions) {
            long reservedBytes = 0;
            for (BundleSession session : sessions.values()) {
                reservedBytes = saturatingAdd(reservedBytes, session.estimatedResidentBytes);
            }
            return new CapacitySnapshot(sessions.size(), reservedBytes);
        }
    }

    private BundleSession leastRecentlyUsedIdleSession(Set<BundleSession> skipped) {
        List<BundleSession> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions.values());
        }
        return snapshot.stream()
                .filter(session -> !skipped.contains(session))
                .filter(this::isIdleAndEvictable)
                .min(Comparator.comparingLong((BundleSession session) -> session.lastAccessSequence)
                        .thenComparing(session -> session.bundleId))
                .orElse(null);
    }

    private boolean isIdleAndEvictable(BundleSession session) {
        synchronized (session) {
            return !session.closing && session.leaseCount == 0 && session.handle != null;
        }
    }

    private BundleSession currentSession(String bundleId) {
        synchronized (sessions) {
            return sessions.get(bundleId);
        }
    }

    private boolean isCurrent(BundleSession session) {
        synchronized (sessions) {
            return sessions.get(session.bundleId) == session;
        }
    }

    private OnnxSessionHandle open(VerifiedOnnxModelBundle bundle, Instant deadline, BundleSession bundleSession)
            throws OnnxSessionAcquireException {
        try {
            Optional<OrtEnvironment> environment = environmentSupplier.get();
            if (environment.isEmpty()) {
                throw new OnnxSessionAcquireException(
                        OnnxSessionAcquireException.Reason.RUNTIME_UNAVAILABLE, "ONNX Runtime is unavailable");
            }
            OnnxSessionHandle handle = sessionFactory.open(environment.get(), bundle);
            bundleSession.handle = Objects.requireNonNull(handle, "sessionFactory result");
            ensureBeforeDeadline(deadline);
            return handle;
        } catch (OnnxSessionAcquireException e) {
            bundleSession.closing = true;
            closeQuietly(bundleSession.handle);
            bundleSession.handle = null;
            closeAndRemove(bundle.manifest().bundleId(), bundleSession);
            throw e;
        } catch (Exception | LinkageError e) {
            bundleSession.closing = true;
            closeQuietly(bundleSession.handle);
            bundleSession.handle = null;
            closeAndRemove(bundle.manifest().bundleId(), bundleSession);
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

    private static int requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long mebibytesToBytes(long value) {
        if (value <= 0) throw new IllegalArgumentException("maxResidentMb must be positive");
        try {
            return Math.multiplyExact(value, BYTES_PER_MEBIBYTE);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("maxResidentMb is too large", e);
        }
    }

    private static long estimateResidentBytes(VerifiedOnnxModelBundle bundle) {
        return bundle.manifest().estimatedResidentBytes();
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static OnnxSessionAcquireException admissionRejected(String message) {
        return new OnnxSessionAcquireException(OnnxSessionAcquireException.Reason.ADMISSION_REJECTED, message);
    }

    private static boolean hasInFlightWork(BundleSession bundleSession) {
        return bundleSession.leaseCount > 0 || !bundleSession.activeRuns.isEmpty();
    }

    private static void requestCancellation(BundleSession bundleSession) {
        for (OrtSession.RunOptions runOptions : bundleSession.activeRuns) {
            try {
                runOptions.setTerminate(true);
            } catch (OrtException e) {
                log.warn("ONNX run cancellation signal failed");
            }
        }
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
        private final long estimatedResidentBytes;
        private final Set<OrtSession.RunOptions> activeRuns = new HashSet<>();
        private OnnxSessionHandle handle;
        private int leaseCount;
        private boolean closing;
        private volatile long lastAccessSequence;

        BundleSession(String bundleId, long estimatedResidentBytes, long lastAccessSequence) {
            this.bundleId = bundleId;
            this.estimatedResidentBytes = estimatedResidentBytes;
            this.lastAccessSequence = lastAccessSequence;
        }
    }

    private record CapacitySnapshot(int loadedModels, long reservedBytes) {}

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

    private record OrtSessionHandle(OrtSession session, HuggingFaceTokenizer tokenizer) implements OnnxSessionHandle {
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
