package com.arcclaw.core.security;

/**
 * Interface for making autonomy decisions.
 */
public interface AutonomyPolicy {

    /**
     * Checks if a specific operation is allowed under the current policy.
     *
     * @param level     The current autonomy level.
     * @param operation The operation to check.
     * @return true if allowed, false if approval or denial is required.
     */
    boolean isAllowed(AutonomyLevel level, ToolOperation operation);

    /**
     * Default implementation of the governance logic.
     */
    class Default implements AutonomyPolicy {
        @Override
        public boolean isAllowed(AutonomyLevel level, ToolOperation operation) {
            return switch (level) {
                case FULL -> true;
                case SUPERVISED -> operation == ToolOperation.READ_FS
                        || operation == ToolOperation.SCHEDULE_AUTONOMOUS;
                case BACKGROUND -> operation == ToolOperation.READ_FS
                        || operation == ToolOperation.NETWORK_OUTBOUND;
                case READONLY -> operation == ToolOperation.READ_FS;
            };
        }
    }
}
