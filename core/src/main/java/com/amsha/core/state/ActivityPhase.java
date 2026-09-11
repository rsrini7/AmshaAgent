package com.amsha.core.state;

import java.util.Locale;

import com.amsha.core.agent.reasoning.ReasoningStep;

/**
 * Vocabulary for the coarse activity phase recorded on a running turn.
 *
 * <p>The phase is surfaced to every interface (Telegram busy message, CLI progress
 * ticker, Web UI status endpoint) so users can see <em>what</em> the agent is
 * doing rather than only that it is busy. Centralising the literals here keeps the
 * producer ({@code ResumeActivityObserver}) and the renderers in sync and avoids
 * raw phase strings leaking across the codebase.
 */
public final class ActivityPhase {

    /** Turn accepted and reasoning, no tool running yet. */
    public static final String RUNNING = "RUNNING";

    /** A parallel tool batch is in flight. */
    public static final String PARALLEL_TOOLS = "PARALLEL_TOOLS";

    /** Tool results are being folded back into the reasoning loop. */
    public static final String OBSERVING = "OBSERVING";

    /** The loop reached FINISH and is drafting the final answer. */
    public static final String FINISHING = "FINISHING";

    /** Prefix for the dynamic {@code TOOL:<toolName>} phase. */
    public static final String TOOL_PREFIX = "TOOL:";

    private static final String FALLBACK_TOOL_NAME = "tool";

    private ActivityPhase() {}

    /** Builds the phase for a named tool, e.g. {@code TOOL:read_file}. */
    public static String tool(String toolName) {
        String name = toolName == null || toolName.isBlank() ? FALLBACK_TOOL_NAME : toolName.strip();
        return TOOL_PREFIX + name;
    }

    /**
     * Derives the phase recorded for a completed reasoning step.
     *
     * @return one of {@link #FINISHING}, {@link #PARALLEL_TOOLS},
     *         {@link #OBSERVING}, {@code TOOL:<action>} or {@link #RUNNING}
     */
    public static String forStep(ReasoningStep step) {
        if (step instanceof ReasoningStep.ToolCallStep) {
            return tool(step.action());
        }
        if (step instanceof ReasoningStep.ParallelStep) {
            return PARALLEL_TOOLS;
        }
        if (step instanceof ReasoningStep.ObservationStep) {
            return OBSERVING;
        }
        if (step instanceof ReasoningStep.FinishStep) {
            return FINISHING;
        }
        return RUNNING;
    }

    /**
     * Human-readable rendering for chat and terminal surfaces, e.g.
     * {@code TOOL:read_file -> "running read_file"}. Empty when there is no phase.
     */
    public static String label(String phase) {
        if (phase == null || phase.isBlank()) {
            return "";
        }
        if (phase.startsWith(TOOL_PREFIX)) {
            return "running " + phase.substring(TOOL_PREFIX.length());
        }
        return switch (phase) {
            case PARALLEL_TOOLS -> "running tools";
            case OBSERVING -> "reading tool results";
            case FINISHING -> "writing the answer";
            case RUNNING -> "thinking";
            default -> phase.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }
}
