package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.llm.api.StructuredOutputMode;

import org.junit.jupiter.api.Test;

class PreflightStateTest {

    @Test
    void testArtifactCompletionAtStepLimitReturnsSuccess() {
        ReasoningStep completedArtifact = ReasoningStep.pending(
                        "Write the artifact and open it.",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of(
                                "code",
                                "mkdir -p output && cat > output/viewer.html <<'EOF'\n<html></html>\nEOF\nopen output/viewer.html"),
                        100,
                        100)
                .withObservation("Opened default browser with viewer.html", true);

        AgentExecutionContext executionContext = new AgentExecutionContext(
                "session-1",
                "user-1",
                "chat-1",
                "skill:viewer",
                "Generate and open the HTML artifact",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                3,
                3,
                null,
                null,
                null,
                null);

        StateContext stateContext = new StateContext(executionContext, null, List.of(completedArtifact));
        stateContext.setBrowserPreflightChecked(true);

        RunnerServices services = new RunnerServices(null, null, null, null, null, null, 0);

        RunnerState nextState = new PreflightState(services).execute(stateContext);

        assertInstanceOf(CompletedState.class, nextState);
        assertInstanceOf(AgentResult.Finished.class, stateContext.finalResult());
        assertEquals(
                "The requested artifact was generated and opened successfully.",
                ((AgentResult.Finished) stateContext.finalResult()).answer());
    }

    @Test
    void testLoopLimitStillAppliesWithoutArtifactCompletion() {
        ReasoningStep unfinished = ReasoningStep.pending(
                        "Keep working.",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of("code", "echo still working"),
                        100,
                        100)
                .withObservation("Still working", true);

        AgentExecutionContext executionContext = new AgentExecutionContext(
                "session-2",
                "user-2",
                "chat-2",
                "coordinator",
                "Do something unrelated",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                3,
                3,
                null,
                null,
                null,
                null);

        StateContext stateContext = new StateContext(executionContext, null, List.of(unfinished));
        stateContext.setBrowserPreflightChecked(true);

        RunnerServices services = new RunnerServices(null, null, null, null, null, null, 0);

        RunnerState nextState = new PreflightState(services).execute(stateContext);

        assertInstanceOf(FailedState.class, nextState);
        assertInstanceOf(AgentResult.LoopExceeded.class, stateContext.finalResult());
        assertEquals(3, ((AgentResult.LoopExceeded) stateContext.finalResult()).maxIterations());
    }
}
