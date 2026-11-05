package com.amsha.core.tool.attempt;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.amsha.core.sandbox.SandboxAttemptLifecycle;

/** Bridges sandbox process events to the durable attempt state machine. */
public final class ToolAttemptSandboxLifecycle implements SandboxAttemptLifecycle {
    private final ToolAttemptExecutionService service;
    private final ToolAttemptExecutionService.AttemptHandle handle;
    private final AtomicBoolean launchIntent = new AtomicBoolean();

    public ToolAttemptSandboxLifecycle(
            ToolAttemptExecutionService service, ToolAttemptExecutionService.AttemptHandle handle) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.handle = Objects.requireNonNull(handle, "handle must not be null");
    }

    @Override
    public void launchRequested(String executionHandle) {
        launchIntent.set(true);
        service.requestLaunch(handle, executionHandle);
    }

    @Override
    public void executionStarting() {
        service.markExecutionStarting(handle);
    }

    @Override
    public void executorStarted(String processStartIdentity) {
        service.markRunning(handle, processStartIdentity);
    }

    @Override
    public void launchFailedBeforeStart() {
        service.markLaunchFailedBeforeStart(handle);
    }

    @Override
    public boolean hasLaunchIntent() {
        return launchIntent.get();
    }
}
