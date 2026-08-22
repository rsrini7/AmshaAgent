package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

class StepDecisionRouterTerminalCorrectionTest {

    @Test
    void rejectedFinishIsRecordedAsNonTerminalObservation() {
        FinishActionHandler finishActionHandler = mock(FinishActionHandler.class);
        ToolStepHandler toolStepHandler = mock(ToolStepHandler.class);
        ChatMemoryWriter writer = mock(ChatMemoryWriter.class);
        EngineStepHandler engineStepHandler = mock(EngineStepHandler.class);
        StepDecisionRouter router =
                new StepDecisionRouter(finishActionHandler, toolStepHandler, writer, engineStepHandler);

        AgentExecutionContext context = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "do memory search for my nickname",
                AutonomyLevel.SUPERVISED,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        ReasoningStep.FinishStep rejectedFinish = new ReasoningStep.FinishStep(
                "done", Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, "Based on information I stored"), 0, 0, false);
        when(finishActionHandler.handle(any(), any(), any(ReasoningStep.FinishStep.class), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.Correction(rejectedFinish, "retry"));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecisionRouter.RoutingOutcome outcome = router.route(context, history, rejectedFinish, 0, 0, List.of());

        assertTrue(outcome.retryEngine());
        assertFalse(outcome.isTerminal());
        assertEquals(1, history.size());
        assertEquals(ReasoningStep.OBSERVE, history.getFirst().action());
        assertTrue(history.getFirst().observation().contains("Rejected FINISH answer"));
    }
}
