package com.amsha.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.api.AgentResponseMapper.AgentResponse;
import com.amsha.core.api.AgentResponseMapper.ApprovalRequiredResponse;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgentResponseMapperTest {

    private final AgentResponseMapper mapper = new AgentResponseMapper();
    private final AuditService auditService = Mockito.mock(AuditService.class);

    @BeforeEach
    void setUp() {
        mapper.auditService = auditService;
    }

    @Test
    void testMapResult_finished_sync_returns200() {
        AgentResult result =
                AgentResult.finished("Done", List.of(ReasoningStep.of("thought", "FINISH", Map.of(), null, 10, 5)));
        var mr = mapper.mapResult(result, false);
        assertEquals(200, mr.suggestedHttpStatus());
        AgentResponse entity = (AgentResponse) mr.entity();
        assertEquals("Done", entity.answer());
        assertEquals(10, entity.totalInputTokens());
        assertEquals(5, entity.totalOutputTokens());
    }

    @Test
    void testMapResult_finished_async_returns202() {
        AgentResult result = AgentResult.finished("Done", List.of());
        var mr = mapper.mapResult(result, true);
        assertEquals(202, mr.suggestedHttpStatus());
        AgentResponse entity = (AgentResponse) mr.entity();
        assertEquals("Done", entity.answer());
    }

    @Test
    void testMapResult_awaitingApproval_returns202WithShape() {
        AgentResult result = AgentResult.awaitingApproval("tool", "{}", "sess1", List.of());
        var mr = mapper.mapResult(result, false);
        assertEquals(202, mr.suggestedHttpStatus());
        ApprovalRequiredResponse entity = (ApprovalRequiredResponse) mr.entity();
        assertEquals("tool", entity.toolName());
        assertEquals("sess1", entity.sessionId());
    }

    @Test
    void testMapResult_loopExceeded_returns408() {
        AgentResult result = AgentResult.loopExceeded(5, List.of());
        var mr = mapper.mapResult(result, false);
        assertEquals(408, mr.suggestedHttpStatus());
        AmshaErrorResponse entity = (AmshaErrorResponse) mr.entity();
        assertTrue(entity.error().contains("5"));
    }

    @Test
    void testMapResult_failed_compliance_returns451() {
        AgentResult result =
                AgentResult.failed("Violation", AgentResult.FailureCategory.COMPLIANCE_VIOLATION, List.of());
        var mr = mapper.mapResult(result, false);
        assertEquals(451, mr.suggestedHttpStatus());
        AmshaErrorResponse entity = (AmshaErrorResponse) mr.entity();
        assertTrue(entity.error().contains("Violation"));
    }

    @Test
    void testMapResult_failed_budget_returns429() {
        AgentResult result = AgentResult.failed("Broke", AgentResult.FailureCategory.BUDGET_EXCEEDED, List.of());
        var mr = mapper.mapResult(result, false);
        assertEquals(429, mr.suggestedHttpStatus());
        AmshaErrorResponse entity = (AmshaErrorResponse) mr.entity();
        assertTrue(entity.error().contains("Broke"));
    }

    @Test
    void testMapResult_failed_userDenied_returns422() {
        AgentResult result = AgentResult.failed("No", AgentResult.FailureCategory.USER_DENIED, List.of());
        var mr = mapper.mapResult(result, false);
        assertEquals(422, mr.suggestedHttpStatus());
        AmshaErrorResponse entity = (AmshaErrorResponse) mr.entity();
        assertTrue(entity.error().contains("No"));
    }

    @Test
    void testMapResult_failed_schemaError_returns422() {
        AgentResult result = AgentResult.failed("Bad shape", AgentResult.FailureCategory.SCHEMA_ERROR, List.of());
        var mr = mapper.mapResult(result, false);
        assertEquals(422, mr.suggestedHttpStatus());
        AmshaErrorResponse entity = (AmshaErrorResponse) mr.entity();
        assertTrue(entity.error().contains("schema error"));
    }

    @Test
    void testMapResult_failed_systemError_returns500() {
        AgentResult result = AgentResult.failed("Crash", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
        var mr = mapper.mapResult(result, false);
        assertEquals(500, mr.suggestedHttpStatus());
        AmshaErrorResponse entity = (AmshaErrorResponse) mr.entity();
        assertTrue(entity.error().contains("Crash"));
    }

    @Test
    void testMapResult_failed_rateLimit_returns429() {
        AgentResult result = AgentResult.failed("Slow down", AgentResult.FailureCategory.RATE_LIMIT, List.of());
        var mr = mapper.mapResult(result, false);
        assertEquals(429, mr.suggestedHttpStatus());
        AmshaErrorResponse entity = (AmshaErrorResponse) mr.entity();
        assertTrue(entity.error().contains("Slow down"));
    }
}
