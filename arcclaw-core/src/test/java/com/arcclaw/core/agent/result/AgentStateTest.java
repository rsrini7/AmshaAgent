package com.arcclaw.core.agent.result;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.arcclaw.core.agent.loop.AgentExecutionContext;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.security.AutonomyLevel;

import org.junit.jupiter.api.Test;

class AgentStateTest {

    @Test
    void testAwaitingApprovalToResult() {
        ReasoningStep step =
                ReasoningStep.pending("thought", "tool", null, 0, 0).withObservation("obs");
        AgentState.AwaitingApproval state = new AgentState.AwaitingApproval("myTool", "{\"p\":1}", List.of(step));

        AgentResult result = state.toResult("session-123");

        assertInstanceOf(AgentResult.AwaitingApproval.class, result);
        AgentResult.AwaitingApproval a = (AgentResult.AwaitingApproval) result;
        assertEquals("myTool", a.toolName());
        assertEquals("{\"p\":1}", a.paramsJson());
        assertEquals("session-123", a.sessionId());
        assertEquals(1, a.steps().size());
    }

    @Test
    void testExecutionContextFromRequest() {
        RunAgentRequest req = new RunAgentRequest("my goal", "coder", 10);
        AgentExecutionContext ctx =
                AgentExecutionContext.from(req, "sid", "uid", "cid", AutonomyLevel.FULL, StructuredOutputMode.NATIVE);

        assertEquals("sid", ctx.sessionId());
        assertEquals("uid", ctx.userId());
        assertEquals("cid", ctx.chatId());
        assertEquals("coder", ctx.agentRole());
        assertEquals("my goal", ctx.goal());
        assertEquals(AutonomyLevel.FULL, ctx.autonomyLevel());
        assertEquals(10, ctx.maxIterations());
    }

    @Test
    void testExecutionContextDefaultIterations() {
        RunAgentRequest req = new RunAgentRequest("goal", "role", null);
        AgentExecutionContext ctx =
                AgentExecutionContext.from(req, "s", "u", "c", AutonomyLevel.SUPERVISED, StructuredOutputMode.NATIVE);
        assertEquals(5, ctx.maxIterations());
    }

    @Test
    void testStatePermits() {
        List<ReasoningStep> steps = List.of();
        assertTrue(new AgentState.InProgress(steps) instanceof AgentState);
        assertTrue(new AgentState.Completed("ans", steps) instanceof AgentState);
        assertTrue(new AgentState.Failed("fail", steps) instanceof AgentState);
        assertTrue(new AgentState.Compromised("bad", steps) instanceof AgentState);
    }
}
