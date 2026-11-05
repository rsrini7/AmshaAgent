package com.amsha.core.tool.attempt;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;

/** Mutable invocation-local state updated only by fenced store transitions. */
class ToolAttemptHandleState {
    private final ToolAttemptStorePort store;
    private final ToolTargetResolution target;
    private final String safeMetadataJson;
    private final AtomicBoolean executionMayHaveStarted = new AtomicBoolean();
    private final AtomicReference<Runnable> postSettlement = new AtomicReference<>();
    private ToolAttemptDto attempt;

    ToolAttemptHandleState(
            ToolAttemptStorePort store, ToolAttemptDto attempt, ToolTargetResolution target, String safeMetadataJson) {
        this.store = Objects.requireNonNull(store);
        this.attempt = Objects.requireNonNull(attempt);
        this.target = Objects.requireNonNull(target);
        this.safeMetadataJson = Objects.requireNonNull(safeMetadataJson);
    }

    ToolAttemptStorePort store() {
        return store;
    }

    public ToolAttemptStatus status() {
        return attempt.status();
    }

    ToolAttemptDto attempt() {
        return attempt;
    }

    ToolTargetResolution target() {
        return target;
    }

    String safeMetadataJson() {
        return safeMetadataJson;
    }

    void update(ToolAttemptDto updatedAttempt) {
        attempt = Objects.requireNonNull(updatedAttempt);
    }

    void markExecutionMayHaveStarted() {
        executionMayHaveStarted.set(true);
    }

    void markLaunchDidNotStart() {
        executionMayHaveStarted.set(false);
    }

    boolean executionMayHaveStarted() {
        return executionMayHaveStarted.get();
    }

    public void afterSettlement(Runnable action) {
        if (!postSettlement.compareAndSet(null, Objects.requireNonNull(action))) {
            throw new IllegalStateException("A post-settlement action is already registered.");
        }
    }

    void completeSettlement() {
        Runnable action = postSettlement.getAndSet(null);
        if (action != null) {
            action.run();
        }
    }
}
