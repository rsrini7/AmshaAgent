package com.amsha.core.agent.result;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.security.api.RiskLevel;

import org.junit.jupiter.api.Test;

class AgentResultTest {

    @Test
    void testFinishedFactoryCalculatesTokens() {
        ReasoningStep s1 = ReasoningStep.of("thought", "tool", null, "obs", 10, 5);
        ReasoningStep s2 = ReasoningStep.of("done", "finish", null, null, 20, 15);

        AgentResult result = AgentResult.finished("ok", List.of(s1, s2));

        assertInstanceOf(AgentResult.Finished.class, result);
        AgentResult.Finished f = (AgentResult.Finished) result;
        assertEquals("ok", f.answer());
        assertEquals(30, f.totalInputTokens());
        assertEquals(20, f.totalOutputTokens());
    }

    @Test
    void testFailedFactoryCalculatesTokens() {
        ReasoningStep s1 = ReasoningStep.of("thought", "tool", null, "obs", 100, 50);

        AgentResult result = AgentResult.failed("boom", AgentResult.FailureCategory.SYSTEM_ERROR, List.of(s1));

        assertInstanceOf(AgentResult.Failed.class, result);
        AgentResult.Failed f = (AgentResult.Failed) result;
        assertEquals("boom", f.reason());
        assertEquals(AgentResult.FailureCategory.SYSTEM_ERROR, f.category());
        assertEquals(100, f.totalInputTokens());
        assertEquals(50, f.totalOutputTokens());
    }

    @Test
    void testAwaitingApprovalFactory() {
        AgentResult result = AgentResult.awaitingApproval("tool", "{}", "sid", List.of());

        assertInstanceOf(AgentResult.AwaitingApproval.class, result);
        AgentResult.AwaitingApproval a = (AgentResult.AwaitingApproval) result;
        assertEquals("tool", a.toolName());
        assertEquals("{}", a.paramsJson());
        assertEquals("sid", a.sessionId());
    }

    @Test
    void testLoopExceededFactory() {
        AgentResult result = AgentResult.loopExceeded(5, List.of());

        assertInstanceOf(AgentResult.LoopExceeded.class, result);
        AgentResult.LoopExceeded l = (AgentResult.LoopExceeded) result;
        assertEquals(5, l.maxIterations());
    }

    @Test
    void testEmptyStepsResultInZeroTokens() {
        AgentResult result = AgentResult.finished("empty", List.of());
        assertEquals(0, result.totalInputTokens());
        assertEquals(0, result.totalOutputTokens());

        result = AgentResult.finished("null", null);
        assertEquals(0, result.totalInputTokens());
        assertEquals(0, result.totalOutputTokens());
    }

    @Test
    void approvalDisplayRedactsSensitiveParametersButRawTransportRetainsThem() {
        AgentResult result = AgentResult.awaitingApproval(
                new ApprovalFrame(
                        "web_fetch",
                        Map.of("url", "https://example.com", "authorization", "Bearer secret"),
                        RiskLevel.HIGH),
                "session-1",
                List.of());

        AgentResult.AwaitingApproval approval = (AgentResult.AwaitingApproval) result;
        assertTrue(approval.rawParamsJson().contains("Bearer secret"));
        assertTrue(approval.displayParamsJson().contains("[REDACTED]"));
        assertFalse(approval.displayParamsJson().contains("Bearer secret"));
    }
}
