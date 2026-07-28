package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

    private static OnnxSessionLeaseManager manager(AtomicInteger opened, AtomicInteger closed) {
        return new OnnxSessionLeaseManager(
                () -> Optional.of(OrtEnvironment.getEnvironment()), fakeFactory(opened, closed), Duration.ofSeconds(1));
    }

    private static OnnxSessionHandleFactory fakeFactory(AtomicInteger opened, AtomicInteger closed) {
        return (environment, bundle) -> {
            opened.incrementAndGet();
            return new OnnxSessionHandle() {
                @Override
                public OrtSession session() {
                    throw new UnsupportedOperationException("This lifecycle test never runs inference");
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
        };
    }

    private static VerifiedOnnxModelBundle bundle() {
        String digest = "0".repeat(64);
        return new VerifiedOnnxModelBundle(
                new OnnxModelBundleManifest("test-model", "v1", "model.onnx", digest, 1, "tokenizer.json", digest, 1),
                Path.of("model.onnx"),
                Path.of("tokenizer.json"));
    }

    private static Instant futureDeadline() {
        return Instant.now().plusSeconds(5);
    }
}
