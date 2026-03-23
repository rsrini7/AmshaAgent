package com.arcclaw.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.ws.rs.core.Response;

import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.api.AgentResponseMapper.AgentResponse;
import com.arcclaw.core.api.AgentResponseMapper.ApprovalRequiredResponse;
import com.arcclaw.core.api.AgentResponseMapper.ErrorResponse;
import org.junit.jupiter.api.Test;

class AgentResponseMapperTest {

    private final AgentResponseMapper mapper = new AgentResponseMapper();

    @Test
    void testMapResult_finished_sync_returns200() {
        AgentResult.Finished result = new AgentResult.Finished("Done", List.of(), 10, 5);
        Response response = mapper.mapResult(result, false);
        assertEquals(200, response.getStatus());
        AgentResponse entity = (AgentResponse) response.getEntity();
        assertEquals("Done", entity.answer());
        assertEquals(10, entity.totalInputTokens());
        assertEquals(5, entity.totalOutputTokens());
    }

    @Test
    void testMapResult_finished_async_returns202() {
        AgentResult.Finished result = new AgentResult.Finished("Done", List.of(), 10, 5);
        Response response = mapper.mapResult(result, true);
        assertEquals(202, response.getStatus());
        AgentResponse entity = (AgentResponse) response.getEntity();
        assertEquals("Done", entity.answer());
    }

    @Test
    void testMapResult_awaitingApproval_returns202WithShape() {
        AgentResult.AwaitingApproval result = new AgentResult.AwaitingApproval("tool", "{}", "sess1", List.of(), 0, 0);
        Response response = mapper.mapResult(result, false);
        assertEquals(202, response.getStatus());
        ApprovalRequiredResponse entity = (ApprovalRequiredResponse) response.getEntity();
        assertEquals("tool", entity.toolName());
        assertEquals("sess1", entity.sessionId());
    }

    @Test
    void testMapResult_loopExceeded_returns408() {
        AgentResult.LoopExceeded result = new AgentResult.LoopExceeded(5, List.of(), 0, 0);
        Response response = mapper.mapResult(result, false);
        assertEquals(408, response.getStatus());
        ErrorResponse entity = (ErrorResponse) response.getEntity();
        assertTrue(entity.reason().contains("5"));
        assertEquals("LOOP_EXCEEDED", entity.code());
    }

    @Test
    void testMapResult_failed_compliance_returns451() {
        AgentResult.Failed result = new AgentResult.Failed("Violation", AgentResult.FailureCategory.COMPLIANCE_VIOLATION, List.of(), 0, 0);
        Response response = mapper.mapResult(result, false);
        assertEquals(451, response.getStatus());
        ErrorResponse entity = (ErrorResponse) response.getEntity();
        assertEquals("COMPLIANCE", entity.code());
    }

    @Test
    void testMapResult_failed_budget_returns429() {
        AgentResult.Failed result = new AgentResult.Failed("Broke", AgentResult.FailureCategory.BUDGET_EXCEEDED, List.of(), 0, 0);
        Response response = mapper.mapResult(result, false);
        assertEquals(429, response.getStatus());
        ErrorResponse entity = (ErrorResponse) response.getEntity();
        assertEquals("BUDGET", entity.code());
    }

    @Test
    void testMapResult_failed_userDenied_returns422() {
        AgentResult.Failed result = new AgentResult.Failed("No", AgentResult.FailureCategory.USER_DENIED, List.of(), 0, 0);
        Response response = mapper.mapResult(result, false);
        assertEquals(422, response.getStatus());
        ErrorResponse entity = (ErrorResponse) response.getEntity();
        assertEquals("USER_DENIED", entity.code());
    }

    @Test
    void testMapResult_failed_schemaError_returns422() {
        AgentResult.Failed result = new AgentResult.Failed("Bad shape", AgentResult.FailureCategory.SCHEMA_ERROR, List.of(), 0, 0);
        Response response = mapper.mapResult(result, false);
        assertEquals(422, response.getStatus());
        ErrorResponse entity = (ErrorResponse) response.getEntity();
        assertEquals("SCHEMA", entity.code());
    }

    @Test
    void testMapResult_failed_systemError_returns500() {
        AgentResult.Failed result = new AgentResult.Failed("Crash", AgentResult.FailureCategory.SYSTEM_ERROR, List.of(), 0, 0);
        Response response = mapper.mapResult(result, false);
        assertEquals(500, response.getStatus());
        ErrorResponse entity = (ErrorResponse) response.getEntity();
        assertEquals("SYSTEM", entity.code());
    }

    @Test
    void testMapResult_failed_rateLimit_returns429() {
        AgentResult.Failed result = new AgentResult.Failed("Slow down", AgentResult.FailureCategory.RATE_LIMIT, List.of(), 0, 0);
        Response response = mapper.mapResult(result, false);
        assertEquals(429, response.getStatus());
        ErrorResponse entity = (ErrorResponse) response.getEntity();
        assertEquals("RATE_LIMIT", entity.code());
    }
}
