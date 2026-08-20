package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.llm.api.StructuredOutputMode;

import org.junit.jupiter.api.Test;

class ParallelToolStepExecutorTest {

    @Test
    void testExecuteEmptyBatchTerminates() {
        ParallelToolStepExecutor executor = new ParallelToolStepExecutor();

        AgentExecutionContext context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coder",
                "solve issue",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                1,
                "telegram",
                "plat-1",
                null,
                Map.of());

        ReasoningStep batchStep = ReasoningStep.parallelBatch("think", Map.of(), 0, 0);

        StepDecision decision = executor.execute(context, List.of(), batchStep, 0);
        assertNotNull(decision);
        assertEquals(DecisionType.TERMINAL, decision.type());
        assertInstanceOf(AgentResult.Failed.class, decision.result());
        AgentResult.Failed failed = (AgentResult.Failed) decision.result();
        assertTrue(failed.reason().contains("empty or corrupt"));
        assertEquals(AgentResult.FailureCategory.SYSTEM_ERROR, failed.category());
    }
}
