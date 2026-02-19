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
            if (level == AutonomyLevel.FULL) {
                return true;
            }
            if (level == AutonomyLevel.READ_ONLY) {
                return operation == ToolOperation.READ_FS;
            }
            if (level == AutonomyLevel.PARTIAL) {
                // Partial allows reads, but flags everything else for approval
                return operation == ToolOperation.READ_FS;
            }
            return false;
        }
    }
}
