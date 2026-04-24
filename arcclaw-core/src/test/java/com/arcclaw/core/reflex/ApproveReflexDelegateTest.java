package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void nonDelegateUserWithSessionIdArg_Rejected() {
        String adminId = "attacker";
        String targetSid = "12345-67890";
        String input = "/approve " + targetSid;
        
        when(delegateGuard.isDelegate(adminId)).thenReturn(false);
        
        MessageContext context = createTestContext(targetSid, adminId, input);
        
        var result = reflex.execute(targetSid, adminId, "chat1", input, context);
        
        assertTrue(result.commandResponse().get().contains("not authorized"));
        verify(agentLoop, never()).run(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void delegateUserWithSessionId_SessionNotFound_Rejected() {
        String adminId = "admin123";
        String targetSid = "12345-67890";
        String input = "/approve " + targetSid;
        
        when(delegateGuard.isDelegate(adminId)).thenReturn(true);
        when(agentStore.findLatestAwaitingApproval(eq(targetSid))).thenReturn(Optional.empty());
        
        MessageContext context = createTestContext(targetSid, adminId, input);
        
        var result = reflex.execute(targetSid, adminId, "chat1", input, context);
        
        assertTrue(result.commandResponse().get().contains("Target session not found"));
    }

    private MessageContext createTestContext(String sessionId, String userId, String text) {
        ChannelUser user = new ChannelUser(userId, "chat1", "TestUser", ChannelType.TELEGRAM);
        ChannelMessage message = new ChannelMessage.TelegramMessage(user, text, "chat1", Instant.now());
        MessageContext context = new MessageContext("chat1", user, message);
        context.setSessionId(sessionId);
        return context;
    }
}
