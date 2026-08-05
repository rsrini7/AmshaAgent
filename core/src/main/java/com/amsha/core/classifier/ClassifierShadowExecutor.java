package com.amsha.core.classifier;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.amsha.core.scope.WorkspaceExecutors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.common.annotation.Identifier;

/** Dedicated bounded executor that prevents shadow classifiers from occupying agent work pools. */
@ApplicationScoped
@Identifier("classifier-shadow")
public class ClassifierShadowExecutor implements Executor {

    private final int threads;
    private final int queueCapacity;
    private final Duration shutdownGrace;
    private volatile ExecutorService executor;

    @Inject
    public ClassifierShadowExecutor(
            @ConfigProperty(name = "amsha.classifier.shadow.threads", defaultValue = "2") int threads,
            @ConfigProperty(name = "amsha.classifier.shadow.queue-capacity", defaultValue = "64") int queueCapacity,
            @ConfigProperty(name = "amsha.classifier.shadow.shutdown-grace", defaultValue = "PT5S")
                    Duration shutdownGrace) {
        this.threads = requirePositive(threads, "threads");
        this.queueCapacity = requirePositive(queueCapacity, "queueCapacity");
        this.shutdownGrace = requireNonNegative(shutdownGrace, "shutdownGrace");
    }

    @PostConstruct
    void initialize() {
        executor = WorkspaceExecutors.wrap(new ThreadPoolExecutor(
                threads,
                threads,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy()));
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        ExecutorService current = executor;
        if (current == null || current.isShutdown()) {
            throw new RejectedExecutionException("Classifier shadow executor is stopped");
        }
        current.execute(command);
    }

    @PreDestroy
    void shutdown() {
        ExecutorService current = executor;
        executor = null;
        if (current == null) return;
        current.shutdown();
        try {
            if (!current.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) current.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.shutdownNow();
        }
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }
}
