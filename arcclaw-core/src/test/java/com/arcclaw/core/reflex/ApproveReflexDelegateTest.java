package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.StatefulAgentStore;
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
    
    ApproveReflex reflex;
    
    @BeforeEach
    void setup() {
        reflex = new ApproveReflex();
        reflex.statefulAgentStore = agentStore;
        reflex.agentLoop = agentLoop;
        reflex.delegateGuard = delegateGuard;
        reflex.checkpointResumeHandler = resumeHandler;
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
        when(agentStore.load(eq(targetSid), anyString())).thenReturn(Optional.empty());
        
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
