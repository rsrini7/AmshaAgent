package com.arcclaw.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.Response;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.api.AgentResponseMapper.AgentResponse;
import com.arcclaw.core.api.AgentResponseMapper.ApprovalRequiredResponse;
import com.arcclaw.core.api.dto.ArcClawErrorResponse;
import org.junit.jupiter.api.Test;

class AgentResponseMapperTest {

    private final AgentResponseMapper mapper = new AgentResponseMapper();

    @Test
    void testMapResult_finished_sync_returns200() {
        AgentResult result = AgentResult.finished("Done", List.of(
            ReasoningStep.of("thought", "FINISH", Map.of(), null, 10, 5)
        ));
        Response response = mapper.mapResult(result, false);
        assertEquals(200, response.getStatus());
        AgentResponse entity = (AgentResponse) response.getEntity();
        assertEquals("Done", entity.answer());
        assertEquals(10, entity.totalInputTokens());
        assertEquals(5, entity.totalOutputTokens());
    }

    @Test
    void testMapResult_finished_async_returns202() {
        AgentResult result = AgentResult.finished("Done", List.of());
        Response response = mapper.mapResult(result, true);
        assertEquals(202, response.getStatus());
        AgentResponse entity = (AgentResponse) response.getEntity();
        assertEquals("Done", entity.answer());
    }

    @Test
    void testMapResult_awaitingApproval_returns202WithShape() {
        AgentResult result = AgentResult.awaitingApproval("tool", "{}", "sess1", List.of());
        Response response = mapper.mapResult(result, false);
        assertEquals(202, response.getStatus());
        ApprovalRequiredResponse entity = (ApprovalRequiredResponse) response.getEntity();
        assertEquals("tool", entity.toolName());
        assertEquals("sess1", entity.sessionId());
    }

    @Test
    void testMapResult_loopExceeded_returns408() {
        AgentResult result = AgentResult.loopExceeded(5, List.of());
        Response response = mapper.mapResult(result, false);
        assertEquals(408, response.getStatus());
        ArcClawErrorResponse entity = (ArcClawErrorResponse) response.getEntity();
        assertTrue(entity.error().contains("5"));
        assertEquals("LOOP_EXCEEDED", entity.detail());
    }

    @Test
    void testMapResult_failed_compliance_returns451() {
        AgentResult result = AgentResult.failed("Violation", AgentResult.FailureCategory.COMPLIANCE_VIOLATION, List.of());
        Response response = mapper.mapResult(result, false);
        assertEquals(451, response.getStatus());
        ArcClawErrorResponse entity = (ArcClawErrorResponse) response.getEntity();
        assertEquals("COMPLIANCE", entity.detail());
    }

    @Test
    void testMapResult_failed_budget_returns429() {
        AgentResult result = AgentResult.failed("Broke", AgentResult.FailureCategory.BUDGET_EXCEEDED, List.of());
        Response response = mapper.mapResult(result, false);
        assertEquals(429, response.getStatus());
        ArcClawErrorResponse entity = (ArcClawErrorResponse) response.getEntity();
        assertEquals("BUDGET", entity.detail());
    }

    @Test
    void testMapResult_failed_userDenied_returns422() {
        AgentResult result = AgentResult.failed("No", AgentResult.FailureCategory.USER_DENIED, List.of());
        Response response = mapper.mapResult(result, false);
        assertEquals(422, response.getStatus());
        ArcClawErrorResponse entity = (ArcClawErrorResponse) response.getEntity();
        assertEquals("USER_DENIED", entity.detail());
    }

    @Test
    void testMapResult_failed_schemaError_returns422() {
        AgentResult result = AgentResult.failed("Bad shape", AgentResult.FailureCategory.SCHEMA_ERROR, List.of());
        Response response = mapper.mapResult(result, false);
        assertEquals(422, response.getStatus());
        ArcClawErrorResponse entity = (ArcClawErrorResponse) response.getEntity();
        assertEquals("SCHEMA", entity.detail());
    }

    @Test
    void testMapResult_failed_systemError_returns500() {
        AgentResult result = AgentResult.failed("Crash", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
        Response response = mapper.mapResult(result, false);
        assertEquals(500, response.getStatus());
        ArcClawErrorResponse entity = (ArcClawErrorResponse) response.getEntity();
        assertEquals("SYSTEM", entity.detail());
    }

    @Test
    void testMapResult_failed_rateLimit_returns429() {
        AgentResult result = AgentResult.failed("Slow down", AgentResult.FailureCategory.RATE_LIMIT, List.of());
        Response response = mapper.mapResult(result, false);
        assertEquals(429, response.getStatus());
        ArcClawErrorResponse entity = (ArcClawErrorResponse) response.getEntity();
        assertEquals("RATE_LIMIT", entity.detail());
    }
}
