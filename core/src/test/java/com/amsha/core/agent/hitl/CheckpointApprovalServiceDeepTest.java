package com.amsha.core.agent.hitl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.loop.CheckpointResolutionException;
import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Deep unit tests for {@link CheckpointApprovalService}.
 * Calls REAL methods; only mocks external dependencies (StatefulAgentStore, AgentSseService, resume handler).
 */
class CheckpointApprovalServiceDeepTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    AgentSseService agentSseService;

    @Mock
    Instance<CheckpointResumeHandler> resumeHandlerInstance;

    @Mock
    CheckpointResumeHandler resumeHandler;

    ObjectMapper objectMapper;
    CheckpointApprovalService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        service = new CheckpointApprovalService(agentStore, agentSseService, objectMapper, resumeHandlerInstance);
        when(agentStore.updateBatchToolDecision(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    private AgentCheckpointRecord createCheckpoint(String sessionId, String role) {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setSessionId(sessionId);
        cp.setAgentRole(role);
        cp.setPendingToolName("github");
        cp.setPendingToolParametersJson("{\"action\":\"view\"}");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        return cp;
    }

    // ── approve ──────────────────────────────────────────────────────────

    @Nested
    class ApproveTests {

        @Test
        void approve_checkpointFound_setsDecisionsAndPublishesEvent() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");
            when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

            Map<String, ToolDecision> decisions = new LinkedHashMap<>();
            decisions.put("github", ToolDecision.APPROVED);

            service.approve("s1", "coordinator", decisions);

            verify(agentStore, never()).save(any());
            verify(agentSseService).publish(eq("s1"), any(AgentSseService.HitlEvent.class));
            assertNotNull(cp.getBatchToolDecisions());
            assertTrue(cp.getBatchToolDecisions().contains("APPROVED"));
        }

        @Test
        void approve_checkpointNotFound_doesNotSaveOrPublish() {
            when(agentStore.load("s1", "coordinator")).thenReturn(Optional.empty());

            service.approve("s1", "coordinator", Map.of());

            verify(agentStore, never()).save(any());
            verify(agentSseService, never()).publish(any(), any());
        }

        @Test
        void approve_multipleDecisions_serializesAll() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"PENDING\",\"sandbox_exec\":\"PENDING\"}");
            when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

            Map<String, ToolDecision> decisions = new LinkedHashMap<>();
            decisions.put("github", ToolDecision.APPROVED);
            decisions.put("sandbox_exec", ToolDecision.APPROVED);

            service.approve("s1", "coordinator", decisions);

            String json = cp.getBatchToolDecisions();
            assertNotNull(json);
            assertTrue(json.contains("github"));
            assertTrue(json.contains("sandbox_exec"));
        }
    }

    // ── approveAll ───────────────────────────────────────────────────────

    @Nested
    class ApproveAllTests {

        @Test
        void approveAll_existingDecisions_setsAllApproved() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"PENDING\",\"sandbox_exec\":\"PENDING\"}");
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            AgentCheckpoint result = service.approveAll("s1", "bulk");

            assertNotNull(result);
            verify(agentStore, never()).save(any());
            verify(agentSseService).publish(eq("s1"), any(AgentSseService.HitlEvent.class));
            // Verify decisions were changed to APPROVED
            String json = cp.getBatchToolDecisions();
            assertTrue(json.contains("APPROVED"));
            assertFalse(json.contains("PENDING"));
        }

        @Test
        void approveAll_noExistingDecisions_isRejectedWithoutWrite() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions(null);
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            CheckpointResolutionException ex =
                    assertThrows(CheckpointResolutionException.class, () -> service.approveAll("s1", "bulk"));
            assertEquals(409, ex.getStatusCode());
            verify(agentStore, never()).save(any());
        }

        @Test
        void approveAll_blankDecisions_isRejectedWithoutWrite() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("  ");
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            CheckpointResolutionException ex =
                    assertThrows(CheckpointResolutionException.class, () -> service.approveAll("s1", "bulk"));
            assertEquals(409, ex.getStatusCode());
            verify(agentStore, never()).save(any());
        }

        @Test
        void approveAll_noCheckpoint_throwsCheckpointResolutionException() {
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.empty());

            assertThrows(CheckpointResolutionException.class, () -> service.approveAll("s1", "bulk"));
        }

        @Test
        void approveAll_noCheckpoint_exceptionHas404Status() {
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.empty());

            CheckpointResolutionException ex =
                    assertThrows(CheckpointResolutionException.class, () -> service.approveAll("s1", "bulk"));
            assertEquals(404, ex.getStatusCode());
        }
    }

    // ── deny ─────────────────────────────────────────────────────────────

    @Nested
    class DenyTests {

        @Test
        void deny_checkpointFound_setsStatusAndPublishesEvent() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

            service.deny("s1", "coordinator", "not allowed");

            assertEquals(CheckpointStatus.DENIED, cp.getStatus());
            verify(agentStore).save(cp);
            verify(agentSseService).publish(eq("s1"), any(AgentSseService.HitlEvent.class));
        }

        @Test
        void deny_checkpointNotFound_doesNothing() {
            when(agentStore.load("s1", "coordinator")).thenReturn(Optional.empty());

            service.deny("s1", "coordinator", "not allowed");

            verify(agentStore, never()).save(any());
            verify(agentSseService, never()).publish(any(), any());
        }
    }

    // ── denyAll ──────────────────────────────────────────────────────────

    @Nested
    class DenyAllTests {

        @Test
        void denyAll_existingDecisions_setsAllDenied() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"PENDING\",\"sandbox_exec\":\"PENDING\"}");
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            AgentCheckpoint result = service.denyAll("s1", "security concern");

            assertNotNull(result);
            verify(agentStore, never()).save(any());
            verify(agentSseService).publish(eq("s1"), any(AgentSseService.HitlEvent.class));
            String json = cp.getBatchToolDecisions();
            assertTrue(json.contains("DENIED"));
            assertFalse(json.contains("PENDING"));
        }

        @Test
        void denyAll_noDecisions_isRejectedWithoutWrite() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions(null);
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            CheckpointResolutionException ex =
                    assertThrows(CheckpointResolutionException.class, () -> service.denyAll("s1", "rejected"));
            assertEquals(409, ex.getStatusCode());
            verify(agentStore, never()).save(any());
        }

        @Test
        void denyAll_noCheckpoint_throwsException() {
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.empty());

            assertThrows(CheckpointResolutionException.class, () -> service.denyAll("s1", "nope"));
        }
    }

    // ── approveTool ──────────────────────────────────────────────────────

    @Nested
    class ApproveToolTests {

        @Test
        void approveTool_updatesExistingPendingDecision() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            AgentCheckpoint result = service.approveTool("s1", "github", "ok");

            assertNotNull(result);
            verify(agentStore, never()).save(any());
            String json = cp.getBatchToolDecisions();
            assertNotNull(json);
            assertTrue(json.contains("github"));
            assertTrue(json.contains("APPROVED"));
        }

        @Test
        void approveTool_existingDecisions_addsToMap() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"PENDING\",\"sandbox_exec\":\"PENDING\"}");
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            service.approveTool("s1", "github", "ok");

            String json = cp.getBatchToolDecisions();
            assertTrue(json.contains("github"));
            assertTrue(json.contains("sandbox_exec"));
        }

        @Test
        void approveTool_noCheckpoint_throwsException() {
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.empty());

            assertThrows(CheckpointResolutionException.class, () -> service.approveTool("s1", "github", "ok"));
        }

        @Test
        void approveTool_publishesApprovedEvent() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            service.approveTool("s1", "github", "ok");

            verify(agentSseService).publish(eq("s1"), any(AgentSseService.HitlEvent.class));
        }

        @Test
        void approveTool_blankExistingDecisions_isRejectedWithoutWrite() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("   ");
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            assertThrows(CheckpointResolutionException.class, () -> service.approveTool("s1", "github", "ok"));
        }
    }

    // ── denyTool ─────────────────────────────────────────────────────────

    @Nested
    class DenyToolTests {

        @Test
        void denyThenApprove_doesNotOverwriteDeniedDecision() throws Exception {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"host_exec\":\"PENDING\"}");
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            service.denyTool("s1", "host_exec", "operator rejected");
            assertThrows(
                    CheckpointResolutionException.class, () -> service.approveTool("s1", "host_exec", "late approval"));

            Map<String, ToolDecision> decisions = objectMapper.readValue(
                    cp.getBatchToolDecisions(), new TypeReference<Map<String, ToolDecision>>() {});
            assertEquals(ToolDecision.DENIED, decisions.get("host_exec"));
        }

        @Test
        void denyTool_noExistingDecisions_createsNewDecisionMap() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            AgentCheckpoint result = service.denyTool("s1", "github", "blocked");

            assertNotNull(result);
            verify(agentStore, never()).save(any());
            String json = cp.getBatchToolDecisions();
            assertTrue(json.contains("DENIED"));
        }

        @Test
        void denyTool_existingDecisions_addsDenialToMap() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"PENDING\",\"sandbox_exec\":\"PENDING\"}");
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            service.denyTool("s1", "github", "blocked");

            String json = cp.getBatchToolDecisions();
            assertTrue(json.contains("github"));
            assertTrue(json.contains("DENIED"));
            assertTrue(json.contains("sandbox_exec"));
        }

        @Test
        void denyTool_noCheckpoint_throwsException() {
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.empty());

            assertThrows(CheckpointResolutionException.class, () -> service.denyTool("s1", "github", "blocked"));
        }

        @Test
        void denyTool_publishesDeniedEvent() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");
            when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

            service.denyTool("s1", "github", "blocked");

            verify(agentSseService).publish(eq("s1"), any(AgentSseService.HitlEvent.class));
        }
    }

    // ── triggerResume ────────────────────────────────────────────────────

    @Nested
    class TriggerResumeTests {

        @Test
        void triggerResume_nullCheckpoint_doesNothing() {
            service.triggerResume(null);

            verify(agentSseService, never()).publish(any(), any());
        }

        @Test
        void triggerResume_noPendingTools_callsResumeHandler() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"APPROVED\"}");
            cp.setChannelPlatformId("tg-123");
            cp.setChannelType("telegram");

            when(resumeHandlerInstance.isResolvable()).thenReturn(true);
            when(resumeHandlerInstance.get()).thenReturn(resumeHandler);
            doReturn(true)
                    .when(resumeHandler)
                    .resumeAfterBatchDecision(anyString(), anyString(), anyString(), anyString(), anyString());
            when(agentStore.findLatestNonArchivedCheckpoint("s1")).thenReturn(Optional.of(cp));

            service.triggerResume(cp);

            verify(resumeHandler)
                    .resumeAfterBatchDecision(eq("s1"), eq("coordinator"), eq("tg-123"), eq("tg-123"), eq("telegram"));
            verify(agentSseService).publish(eq("s1"), any(AgentSseService.HitlEvent.class));
        }

        @Test
        void triggerResume_stillPendingTools_skipsResume() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");

            service.triggerResume(cp);

            verify(resumeHandler, never()).resumeAfterBatchDecision(any(), any(), any(), any(), any());
        }

        @Test
        void triggerResume_malformedDecisions_skipsResume() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("not-json");

            service.triggerResume(cp);

            verify(resumeHandler, never()).resumeAfterBatchDecision(any(), any(), any(), any(), any());
            verify(agentSseService, never()).publish(any(), any());
        }

        @Test
        void triggerResume_handlerNotResolvable_doesNotPublishResumedEvent() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"APPROVED\"}");

            when(resumeHandlerInstance.isResolvable()).thenReturn(false);

            service.triggerResume(cp);

            verify(agentSseService, never()).publish(any(), any());
        }

        @Test
        void triggerResume_handlerThrows_doesNotPublishResumedEvent() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"APPROVED\"}");

            when(resumeHandlerInstance.isResolvable()).thenReturn(true);
            when(resumeHandlerInstance.get()).thenThrow(new RuntimeException("boom"));

            service.triggerResume(cp);

            verify(agentSseService, never()).publish(any(), any());
        }

        @Test
        void triggerResume_nullChannelPlatformId_usesFallbacks() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{\"github\":\"APPROVED\"}");
            cp.setChannelPlatformId(null);
            cp.setChannelType(null);

            when(resumeHandlerInstance.isResolvable()).thenReturn(true);
            when(resumeHandlerInstance.get()).thenReturn(resumeHandler);

            service.triggerResume(cp);

            verify(resumeHandler)
                    .resumeAfterBatchDecision(eq("s1"), eq("coordinator"), eq("unknown"), eq("s1"), eq("websocket"));
        }

        @Test
        void triggerResume_emptyBatchDecisions_treatsAsNoPending() {
            AgentCheckpointRecord cp = createCheckpoint("s1", "coordinator");
            cp.setBatchToolDecisions("{}");

            when(resumeHandlerInstance.isResolvable()).thenReturn(true);
            when(resumeHandlerInstance.get()).thenReturn(resumeHandler);

            service.triggerResume(cp);

            verify(resumeHandler).resumeAfterBatchDecision(any(), any(), any(), any(), any());
        }
    }
}
