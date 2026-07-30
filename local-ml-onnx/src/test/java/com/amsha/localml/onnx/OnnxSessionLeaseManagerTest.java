package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class OnnxSessionLeaseManagerTest {

    @Test
    void closesOnlyAfterFinalLeaseReturns() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        OnnxSessionLeaseManager manager = manager(opened, closed);

        OnnxSessionLease first = manager.acquire(bundle(), futureDeadline());
        OnnxSessionLease second = manager.acquire(bundle(), futureDeadline());

        assertEquals(1, opened.get());
        assertEquals(OnnxSessionLeaseManager.UnloadResult.DRAINING, manager.unload("test-model", Duration.ZERO));
        assertEquals(0, closed.get());
        assertEquals(
                OnnxSessionAcquireException.Reason.BUNDLE_CLOSING,
                assertThrows(OnnxSessionAcquireException.class, () -> manager.acquire(bundle(), futureDeadline()))
                        .reason());

        first.close();
        assertEquals(0, closed.get());
        second.close();
        assertEquals(1, closed.get());
        assertEquals(OnnxSessionLeaseManager.UnloadResult.NOT_LOADED, manager.unload("test-model", Duration.ZERO));
    }

    @Test
    void closingLeaseIsIdempotent() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        OnnxSessionLeaseManager manager = manager(opened, closed);

        OnnxSessionLease lease = manager.acquire(bundle(), futureDeadline());
        lease.close();
        lease.close();

        assertEquals(OnnxSessionLeaseManager.UnloadResult.UNLOADED, manager.unload("test-model", Duration.ZERO));
        assertEquals(1, opened.get());
        assertEquals(1, closed.get());
    }

    @Test
    void rejectsExpiredDeadlinesWithoutOpeningSession() {
        AtomicInteger opened = new AtomicInteger();
        OnnxSessionLeaseManager manager = manager(opened, new AtomicInteger());

        OnnxSessionAcquireException error =
                assertThrows(OnnxSessionAcquireException.class, () -> manager.acquire(bundle(), Instant.now()));

        assertEquals(OnnxSessionAcquireException.Reason.DEADLINE_EXCEEDED, error.reason());
        assertEquals(0, opened.get());
    }

    @Test
    void rejectsUnavailableRuntimeWithoutOpeningSession() {
        AtomicInteger opened = new AtomicInteger();
        OnnxSessionLeaseManager manager = new OnnxSessionLeaseManager(
                Optional::<OrtEnvironment>empty, fakeFactory(opened, new AtomicInteger()), Duration.ZERO);

        OnnxSessionAcquireException error =
                assertThrows(OnnxSessionAcquireException.class, () -> manager.acquire(bundle(), futureDeadline()));

        assertEquals(OnnxSessionAcquireException.Reason.RUNTIME_UNAVAILABLE, error.reason());
        assertEquals(0, opened.get());
    }

    @Test
    void evictsLeastRecentlyUsedIdleSessionAtLoadedModelLimit() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        OnnxSessionLeaseManager manager = manager(opened, closed, 2, 1024);

        manager.acquire(bundle("bundle-a", 1, 1), futureDeadline()).close();
        manager.acquire(bundle("bundle-b", 1, 1), futureDeadline()).close();
        manager.acquire(bundle("bundle-a", 1, 1), futureDeadline()).close();
        manager.acquire(bundle("bundle-c", 1, 1), futureDeadline()).close();

        assertEquals(3, opened.get());
        assertEquals(1, closed.get());
        assertEquals(OnnxSessionLeaseManager.UnloadResult.NOT_LOADED, manager.unload("bundle-b", Duration.ZERO));
        assertEquals(OnnxSessionLeaseManager.UnloadResult.UNLOADED, manager.unload("bundle-a", Duration.ZERO));
        assertEquals(OnnxSessionLeaseManager.UnloadResult.UNLOADED, manager.unload("bundle-c", Duration.ZERO));
    }

    @Test
    void rejectsAdmissionRatherThanEvictingActiveSession() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        OnnxSessionLeaseManager manager = manager(opened, closed, 1, 1024);
        OnnxSessionLease active = manager.acquire(bundle("bundle-a", 1, 1), futureDeadline());

        OnnxSessionAcquireException error = assertThrows(
                OnnxSessionAcquireException.class, () -> manager.acquire(bundle("bundle-b", 1, 1), futureDeadline()));

        assertEquals(OnnxSessionAcquireException.Reason.ADMISSION_REJECTED, error.reason());
        assertEquals(1, opened.get());
        assertEquals(0, closed.get());
        active.close();
    }

    @Test
    void residentBudgetEvictsIdleSessionUsingManifestEstimate() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        OnnxSessionLeaseManager manager = manager(opened, closed, 10, 1);

        manager.acquire(bundle("bundle-a", 300_000, 1), futureDeadline()).close();
        manager.acquire(bundle("bundle-b", 300_000, 1), futureDeadline()).close();

        assertEquals(2, opened.get());
        assertEquals(1, closed.get());
        assertEquals(OnnxSessionLeaseManager.UnloadResult.NOT_LOADED, manager.unload("bundle-a", Duration.ZERO));
        assertEquals(OnnxSessionLeaseManager.UnloadResult.UNLOADED, manager.unload("bundle-b", Duration.ZERO));
    }

    @Test
    void rejectsBundleLargerThanResidentBudgetWithoutOpeningSession() {
        AtomicInteger opened = new AtomicInteger();
        OnnxSessionLeaseManager manager = manager(opened, new AtomicInteger(), 10, 1);

        OnnxSessionAcquireException error = assertThrows(
                OnnxSessionAcquireException.class,
                () -> manager.acquire(bundle("oversized", 600_000, 1), futureDeadline()));

        assertEquals(OnnxSessionAcquireException.Reason.ADMISSION_REJECTED, error.reason());
        assertEquals(0, opened.get());
    }

    @Test
    void failedOpenReleasesAdmissionReservation() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        OnnxSessionLeaseManager manager = new OnnxSessionLeaseManager(
                () -> Optional.of(OrtEnvironment.getEnvironment()),
                (environment, bundle) -> {
                    if (attempts.incrementAndGet() == 1) throw new IllegalStateException("simulated load failure");
                    return fakeHandle(closed);
                },
                Duration.ofSeconds(1),
                1,
                1);

        OnnxSessionAcquireException first = assertThrows(
                OnnxSessionAcquireException.class, () -> manager.acquire(bundle("bundle-a", 1, 1), futureDeadline()));
        OnnxSessionLease second = manager.acquire(bundle("bundle-b", 1, 1), futureDeadline());

        assertEquals(OnnxSessionAcquireException.Reason.SESSION_LOAD_FAILED, first.reason());
        assertEquals(2, attempts.get());
        second.close();
    }

    @Test
    void unavailableRuntimeReleasesAdmissionReservation() throws Exception {
        AtomicInteger environmentLookups = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        OnnxSessionLeaseManager manager = new OnnxSessionLeaseManager(
                () -> environmentLookups.getAndIncrement() == 0
                        ? Optional.empty()
                        : Optional.of(OrtEnvironment.getEnvironment()),
                fakeFactory(opened, closed),
                Duration.ofSeconds(1),
                1,
                1024);

        OnnxSessionAcquireException first = assertThrows(
                OnnxSessionAcquireException.class, () -> manager.acquire(bundle("bundle-a", 1, 1), futureDeadline()));
        OnnxSessionLease second = manager.acquire(bundle("bundle-b", 1, 1), futureDeadline());

        assertEquals(OnnxSessionAcquireException.Reason.RUNTIME_UNAVAILABLE, first.reason());
        assertEquals(1, opened.get());
        second.close();
    }

    @Test
    void shutdownRejectsNewLeases() {
        OnnxSessionLeaseManager manager = manager(new AtomicInteger(), new AtomicInteger());

        manager.shutdown();
        OnnxSessionAcquireException error =
                assertThrows(OnnxSessionAcquireException.class, () -> manager.acquire(bundle(), futureDeadline()));

        assertEquals(OnnxSessionAcquireException.Reason.BUNDLE_CLOSING, error.reason());
    }

    @Test
    void drainingBundleRejectsGraphRunThatHasNotStarted() throws Exception {
        OnnxSessionLeaseManager manager = manager(new AtomicInteger(), new AtomicInteger());
        OnnxSessionLease lease = manager.acquire(bundle(), futureDeadline());

        assertEquals(OnnxSessionLeaseManager.UnloadResult.DRAINING, manager.unload("test-model", Duration.ZERO));
        assertThrows(IllegalStateException.class, () -> lease.run(Map.of()));

        lease.close();
    }

    private static OnnxSessionLeaseManager manager(AtomicInteger opened, AtomicInteger closed) {
        return new OnnxSessionLeaseManager(
                () -> Optional.of(OrtEnvironment.getEnvironment()), fakeFactory(opened, closed), Duration.ofSeconds(1));
    }

    private static OnnxSessionLeaseManager manager(
            AtomicInteger opened, AtomicInteger closed, int maxLoadedModels, long maxResidentMb) {
        return new OnnxSessionLeaseManager(
                () -> Optional.of(OrtEnvironment.getEnvironment()),
                fakeFactory(opened, closed),
                Duration.ofSeconds(1),
                maxLoadedModels,
                maxResidentMb);
    }

    private static OnnxSessionHandleFactory fakeFactory(AtomicInteger opened, AtomicInteger closed) {
        return (environment, bundle) -> {
            opened.incrementAndGet();
            return fakeHandle(closed);
        };
    }

    private static OnnxSessionHandle fakeHandle(AtomicInteger closed) {
        return new OnnxSessionHandle() {
            @Override
            public OrtSession session() {
                throw new UnsupportedOperationException("This lifecycle test never runs inference");
            }

            @Override
            public HuggingFaceTokenizer tokenizer() {
                throw new UnsupportedOperationException("This lifecycle test never tokenizes");
            }

            @Override
            public void close() {
                closed.incrementAndGet();
            }
        };
    }

    private static VerifiedOnnxModelBundle bundle() {
        return bundle("test-model", 1, 1);
    }

    private static VerifiedOnnxModelBundle bundle(String bundleId, long modelBytes, long tokenizerBytes) {
        String digest = "0".repeat(64);
        return new VerifiedOnnxModelBundle(
                new OnnxModelBundleManifest(
                        bundleId, "v1", "model.onnx", digest, modelBytes, "tokenizer.json", digest, tokenizerBytes),
                Path.of(bundleId, "model.onnx"),
                Path.of(bundleId, "tokenizer.json"));
    }

    private static Instant futureDeadline() {
        return Instant.now().plusSeconds(5);
    }
}
