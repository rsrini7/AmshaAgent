package com.amsha.core.agent.reasoning;

/** Shared predicates over {@link ReasoningStep} history entries. */
public final class ReasoningStepSupport {

    private ReasoningStepSupport() {}

    /** True when the step is an explicit tool-call boundary (including pending/null observation). */
    public static boolean isToolCallStep(ReasoningStep step) {
        return step instanceof ReasoningStep.ToolCallStep;
    }
}
