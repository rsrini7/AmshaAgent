package com.amsha.core.sandbox;

/** Callbacks that bind sandbox launch evidence to a durable tool attempt. */
public interface SandboxAttemptLifecycle {
    SandboxAttemptLifecycle NONE = new SandboxAttemptLifecycle() {
        @Override
        public void launchRequested(String executionHandle) {}

        @Override
        public void executionStarting() {}

        @Override
        public void executorStarted(String processStartIdentity) {}

        @Override
        public void launchFailedBeforeStart() {}

        @Override
        public boolean hasLaunchIntent() {
            return false;
        }
    };

    /** Persists the exact handle before an external executor is invoked. */
    void launchRequested(String executionHandle);

    /** Records in memory that execution may have begun, immediately before process start. */
    void executionStarting();

    /** Persists confirmation that the external executor started. */
    void executorStarted(String processStartIdentity);

    /** Clears in-memory launch uncertainty when process creation is confirmed to have failed. */
    void launchFailedBeforeStart();

    /** Indicates whether the durable launch intent has been committed for this invocation. */
    boolean hasLaunchIntent();
}
