package com.arcclaw.core.agent.reasoning;

import java.util.Map;

import io.quarkus.qute.TemplateData;

/**
 * One tick of the Thought-Plan-Act-Observe reasoning loop.
 * <p>
 * Each step captures the agent's internal reasoning ({@code thought}),
 * the chosen action ({@code action} — a tool name or {@code "FINISH"}),
 * the parameters for that action ({@code actionInput}), and the
 * result observed after execution ({@code observation}).
 *
 * @param thought      The agent's internal reasoning for this step.
 * @param action       Tool name to invoke, or {@code "FINISH"} to end the loop.
 * @param actionInput  Parameters for the tool (may be empty for FINISH).
 * @param observation  The result of executing the action (filled by the loop).
 * @param inputTokens  LLM input tokens consumed in this step.
 * @param outputTokens LLM output tokens generated in this step.
 */
@TemplateData
public record ReasoningStep(
        String thought,
        String action,
        Map<String, Object> actionInput,
        String observation,
        int inputTokens,
        int outputTokens) {

    /** Action value indicating the loop should terminate with a final answer. */
    public static final String FINISH = "FINISH";

    /** Action value indicating a system-level observation (e.g. error) needs LLM correction. */
    public static final String OBSERVE = "OBSERVE";

    /**
     * Create a step with no observation yet (pre-execution). Tokens are typically 0
     * until known.
     */
    public static ReasoningStep pending(String thought, String action, Map<String, Object> actionInput) {
        return new ReasoningStep(thought, action, actionInput, null, 0, 0);
    }

    /** Create a step with tokens explicitly provided. */
    public static ReasoningStep pending(String thought, String action, Map<String, Object> actionInput, int inputTk,
            int outputTk) {
        return new ReasoningStep(thought, action, actionInput, null, inputTk, outputTk);
    }

    /** Create a step representing a schema/parsing error to trigger LLM retry. */
    public static ReasoningStep schemaError(String observation, int inputTk, int outputTk) {
        return new ReasoningStep("Parsing Error", OBSERVE, Map.of(), observation, inputTk, outputTk);
    }

    /** Create a copy of this step with the observation filled in. */
    public ReasoningStep withObservation(String observation) {
        return new ReasoningStep(this.thought, this.action, this.actionInput, observation, this.inputTokens,
                this.outputTokens);
    }
}
