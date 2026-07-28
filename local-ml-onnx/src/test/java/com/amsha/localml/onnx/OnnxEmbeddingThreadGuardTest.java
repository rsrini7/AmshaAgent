package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.vertx.core.Vertx;

class OnnxEmbeddingThreadGuardTest {

    @Test
    void rejectsActualEventLoopThread() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            CompletableFuture<Throwable> result = new CompletableFuture<>();
            vertx.runOnContext(ignored -> {
                try {
                    OnnxEmbeddingProviderFactory.rejectEventLoopThread();
                    result.complete(null);
                } catch (Throwable failure) {
                    result.complete(failure);
                }
            });

            assertInstanceOf(IllegalStateException.class, result.get(5, TimeUnit.SECONDS));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void allowsVirtualThread() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                OnnxEmbeddingProviderFactory.rejectEventLoopThread();
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });

        thread.join();
        assertNull(failure.get());
    }
}
