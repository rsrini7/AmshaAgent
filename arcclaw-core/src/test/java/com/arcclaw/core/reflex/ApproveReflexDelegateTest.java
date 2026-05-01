package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import jakarta.enterprise.event.Event;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.loop.CheckpointApprovalService;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.agent.loop.CliResultBridge;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.StatefulAgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApproveReflexDelegateTest {

    @Mock StatefulAgentStore agentStore;
    @Mock AgentLoop agentLoop;
    @Mock DelegateApprovalGuard delegateGuard;
    @Mock CheckpointResumeHandler resumeHandler;
    @Mock AgentCronRepository cronRepository;
    @Mock ReasoningEngine reasoningEngine;
    @Mock AuditService auditService;
    @Mock ObjectMapper objectMapper;
    @Mock CheckpointApprovalService checkpointApprovalService;
    @Mock CliResultBridge cliResultBridge;
    @Mock WorkspaceManager workspaceManager;
    @Mock Event<AgentJobCompleted> jobCompletedEvent;
    
    ApproveReflex reflex;
    
    @BeforeEach
    void setup() {
        reflex = new ApproveReflex();
        reflex.statefulAgentStore = agentStore;
        reflex.agentLoop = agentLoop;
        reflex.delegateGuard = delegateGuard;
        reflex.checkpointResumeHandler = resumeHandler;
        reflex.cronRepository = cronRepository;
        reflex.reasoningEngine = reasoningEngine;
        reflex.auditService = auditService;
        reflex.objectMapper = objectMapper;
        reflex.checkpointApprovalService = checkpointApprovalService;
        reflex.cliResultBridge = cliResultBridge;
        reflex.workspaceManager = workspaceManager;
        reflex.jobCompletedEvent = jobCompletedEvent;
        reflex.log = mock(Logger.class);
    }

    @Test
    void nonDelegateUserWithSessionIdArg_AllowedAsOwner() {
        String adminId = "attacker";
        String targetSid = "b2ada049-88e1-474d-9589-783a82190d46";
        String input = "/approve " + targetSid;
        
        // Session exists with pending approval — allowed because user is the owner
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getAgentRole()).thenReturn("coordinator");
        when(agentStore.findLatestAwaitingApproval(eq(targetSid))).thenReturn(Optional.of(cp));
        lenient().when(delegateGuard.isDelegate(adminId)).thenReturn(false);
        
        MessageContext context = createTestContext(targetSid, adminId, input);
        
        var result = reflex.execute(targetSid, adminId, "chat1", input, context);
        
        assertTrue(result.commandResponse().isPresent());
        assertEquals("", result.commandResponse().get());
    }

    @Test
    void anyUser_withUUID_noSessionFound_getsNotFoundMessage() {
        String adminId = "admin123";
        String targetSid = "b2ada049-88e1-474d-9589-783a82190d46";
        String input = "/approve " + targetSid;
        
        // No pending session — falls through session path to cron path to "not found"
        when(agentStore.findLatestAwaitingApproval(eq(targetSid))).thenReturn(Optional.empty());
        
        MessageContext context = createTestContext(targetSid, adminId, input);
        
        var result = reflex.execute(targetSid, adminId, "chat1", input, context);
        
        assertTrue(result.commandResponse().get().contains("Target session not found or has no pending approval."));
    }

    @Test
    void explicitSelfApprovalById_AllowedWithoutDelegateRole() {
        // Verifies: owners CAN explicitly approve their own sessions by ID without delegate role.
        String ownerId = "regularUser";
        String mySessionId = "b2ada049-88e1-474d-9589-783a82190d46";
        String input = "/approve " + mySessionId;
        
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getAgentRole()).thenReturn("coordinator");
        when(agentStore.findLatestAwaitingApproval(eq(mySessionId))).thenReturn(Optional.of(cp));
        lenient().when(delegateGuard.isDelegate(ownerId)).thenReturn(false);
        
        MessageContext context = createTestContext(mySessionId, ownerId, input);
        var result = reflex.execute(mySessionId, ownerId, "chat1", input, context);
        
        assertTrue(result.commandResponse().isPresent());
        assertEquals("", result.commandResponse().get());
    }

    @Test
    void selfApproval_AllowedForDelegates() throws Exception {
        String adminId = "delegateUser";
        String mySessionId = "b2ada049-88e1-474d-9589-783a82190d46";
        String input = "/approve " + mySessionId;
        
        lenient().when(delegateGuard.isDelegate(adminId)).thenReturn(true);
        
        // Mock the pending checkpoint for their own session
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getAgentRole()).thenReturn("coordinator");
        when(cp.hasBatchToolDecisions()).thenReturn(false);
        when(agentStore.findLatestAwaitingApproval(eq(mySessionId))).thenReturn(Optional.of(cp));
        
        MessageContext context = createTestContext(mySessionId, adminId, input);
        var result = reflex.execute(mySessionId, adminId, "chat1", input, context);
        
        // Even for delegates, the self-session fast-path returns "" 
        assertTrue(result.commandResponse().isPresent());
        assertEquals("", result.commandResponse().get());
    }

    @Test
    void approve_withCronUUID_routesToCronHandler() {
        // Exact scenario from production bug: /approve <uuid> where the UUID is a cron job ID,
        // not a session. The runtime disambiguation chain should resolve it via the cron path
        // without requiring delegate role (task owner approving their own cron).
        String userId = "regularUser";
        String cronUUID = "153d443c-84f5-4123-873b-22565fc61f31";
        String sessionId = "current-session-id";
        String input = "/approve " + cronUUID;

        // 1. Not a pending session
        when(agentStore.findLatestAwaitingApproval(eq(cronUUID))).thenReturn(Optional.empty());
        // 2. Not an active main-chat session either — so handleCronDecision won't bail
        when(agentStore.isSessionActive(cronUUID)).thenReturn(false);

        // Mock the checkpoint approval so we reach the cron repository lookup
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getSessionId()).thenReturn("cron-job123-exec456");
        when(checkpointApprovalService.approveAll(eq(cronUUID), any())).thenReturn(cp);

        MessageContext context = createTestContext(sessionId, userId, input);

        var result = reflex.execute(sessionId, userId, "chat1", input, context);

        // The cron path is attempted. Since we haven't mocked the full cron chain,
        // it will throw (CheckpointResolutionException) and fall through to "not found".
        // The critical assertion: we did NOT get "not authorized" — regular users can
        // approve cron tasks without delegate role.
        assertTrue(result.commandResponse().isPresent());
        String response = result.commandResponse().get();
        assertTrue(!response.contains("not authorized"),
                "Cron task approval by owner should not require delegate role, got: " + response);

        // Verify the cron path was actually attempted
        verify(cronRepository, atLeastOnce()).findById(any());
    }

    private MessageContext createTestContext(String sessionId, String userId, String text) {
        ChannelUser user = new ChannelUser(userId, "chat1", "TestUser", ChannelType.TELEGRAM);
        ChannelMessage message = new ChannelMessage.TelegramMessage(user, text, "chat1", Instant.now());
        MessageContext context = new MessageContext("chat1", user, message);
        context.setSessionId(sessionId);
        return context;
    }
}
