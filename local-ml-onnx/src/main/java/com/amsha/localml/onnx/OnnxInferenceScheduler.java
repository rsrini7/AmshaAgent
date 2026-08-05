package com.amsha.localml.onnx;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.amsha.core.scope.WorkspaceExecutors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Bounded CPU inference work queue. It propagates workspace context but never performs model
 * loading, inference, or caller-thread blocking itself.
 */
@ApplicationScoped
public class OnnxInferenceScheduler {

    private final int inferenceThreads;
    private final int queueCapacity;
    private final Duration shutdownGrace;
    private final OnnxInferenceMetrics metrics;
    private volatile ExecutorService executor;

    @Inject
    public OnnxInferenceScheduler(
            @ConfigProperty(name = "amsha.onnx.inference-threads", defaultValue = "2") int inferenceThreads,
            @ConfigProperty(name = "amsha.onnx.queue-capacity", defaultValue = "64") int queueCapacity,
            @ConfigProperty(name = "amsha.onnx.shutdown-grace", defaultValue = "PT10S") Duration shutdownGrace,
            OnnxInferenceMetrics metrics) {
        if (inferenceThreads <= 0) throw new IllegalArgumentException("inferenceThreads must be positive");
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity must be positive");
        this.inferenceThreads = inferenceThreads;
        this.queueCapacity = queueCapacity;
        this.shutdownGrace = requireNonNegative(shutdownGrace, "shutdownGrace");
        this.metrics = metrics;
    }

    OnnxInferenceScheduler(int inferenceThreads, int queueCapacity, Duration shutdownGrace) {
        this(inferenceThreads, queueCapacity, shutdownGrace, null);
    }

    @PostConstruct
    void initialize() {
        executor = WorkspaceExecutors.wrap(newExecutor(inferenceThreads, queueCapacity));
    }

    /**
     * Submits work without waiting for its result. The task is rejected when the bounded queue is
     * full, and an accepted task that begins after its absolute deadline completes as a timeout.
     */
    public <T> CompletableFuture<T> submit(Instant deadline, Callable<T> task) throws OnnxInferenceSchedulingException {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(task, "task");
        if (!Instant.now().isBefore(deadline)) {
            throw new OnnxInferenceSchedulingException(
                    OnnxInferenceSchedulingException.Reason.TIMEOUT, "ONNX inference request deadline exceeded");
        }
        ExecutorService currentExecutor = executor;
        if (currentExecutor == null || currentExecutor.isShutdown()) {
            throw new OnnxInferenceSchedulingException(
                    OnnxInferenceSchedulingException.Reason.SCHEDULER_STOPPED, "ONNX inference scheduler is stopped");
        }
        CancellableInferenceFuture<T> result = new CancellableInferenceFuture<>();
        try {
            Future<?> worker = currentExecutor.submit(() -> execute(deadline, task, result));
            result.attach(worker);
            recordStage("scheduler_accepted");
        } catch (RejectedExecutionException e) {
            throw new OnnxInferenceSchedulingException(
                    OnnxInferenceSchedulingException.Reason.OVERLOAD_REJECTED, "ONNX inference queue is full");
        }
        return result;
    }

    @PreDestroy
    void shutdown() {
        ExecutorService executorToClose = executor;
        executor = null;
        if (executorToClose == null) return;
        executorToClose.shutdown();
        try {
            if (!executorToClose.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                executorToClose.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorToClose.shutdownNow();
        }
    }

    private <T> void execute(Instant deadline, Callable<T> task, CompletableFuture<T> result) {
        recordStage("scheduler_started");
        if (!Instant.now().isBefore(deadline)) {
            result.completeExceptionally(new OnnxInferenceSchedulingException(
                    OnnxInferenceSchedulingException.Reason.TIMEOUT,
                    "ONNX inference request deadline exceeded before execution"));
            return;
        }
        try {
            result.complete(task.call());
        } catch (Exception | Error e) {
            result.completeExceptionally(e);
        }
    }

    private static ThreadPoolExecutor newExecutor(int threads, int capacity) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** A caller timeout must remove queued work and interrupt work already running. */
    private static final class CancellableInferenceFuture<T> extends CompletableFuture<T> {
        private volatile Future<?> worker;

        void attach(Future<?> worker) {
            this.worker = Objects.requireNonNull(worker, "worker");
            if (isCancelled()) worker.cancel(true);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            Future<?> currentWorker = worker;
            if (cancelled && currentWorker != null) currentWorker.cancel(mayInterruptIfRunning);
            return cancelled;
        }
    }

    private void recordStage(String stage) {
        if (metrics != null) metrics.recordExecutionStage(stage);
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }
}
