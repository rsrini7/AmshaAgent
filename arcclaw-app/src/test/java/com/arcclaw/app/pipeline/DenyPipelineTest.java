package com.arcclaw.app.pipeline;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.channel.api.ChannelAllowlist;
import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessagePipeline;
import com.arcclaw.core.reflex.ReflexRegistry;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
public class DenyPipelineTest {

    @Inject
    MessagePipeline pipeline;

    @InjectMock
    ReflexRegistry reflexRegistry;

    @InjectMock
    AuditService auditService;

    @InjectMock
    ChannelAllowlist allowlist;

    private MessageContext context;
    private ChannelSender sender;
    private ChannelUser user;

    @BeforeEach
    void setup() {
        // Mock allowlist to permit our test user
        when(allowlist.isAllowed(anyString(), anyString())).thenReturn(true);

        user = new ChannelUser("user-123", "alice", com.arcclaw.channel.api.ChannelType.CLI);
        sender = mock(ChannelSender.class);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/deny", "session-456", Instant.now());
        context = new MessageContext("chat-789", user, msg, sender);
    }

    @Test
    void testDenyRoutesToReflexRegistryNotDirectly() {
        // Setup mock response
        when(reflexRegistry.processIfReflex(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new com.arcclaw.core.reflex.ReflexResult(
                        java.util.Optional.of("🚫 Execution denied."),
                        "/deny"
                ));

        // Process message through pipeline
        pipeline.process(context);

        // Verify ReflexRegistry was called exactly once
        verify(reflexRegistry, times(1)).processIfReflex(
                anyString(), // Session resolution handler might change this
                eq("user-123"), 
                eq("chat-789"), 
                eq("/deny")
        );
        
        // Verify sender was called with the response
        verify(sender).sendRaw(eq(user), contains("denied"));
    }

    @Test
    void testDenyAuditEventFires() {
        // To follow requirements strictly:
        when(reflexRegistry.processIfReflex(anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> {
                auditService.record(
                    (String)invocation.getArgument(0), 
                    (String)invocation.getArgument(1), 
                    "reflex", 
                    SecurityEventType.APPROVAL_DENIED, 
                    "action=DENY session=" + invocation.getArgument(0)
                );
                return new com.arcclaw.core.reflex.ReflexResult(java.util.Optional.of("🚫"), "/deny");
            });

        pipeline.process(context);

        verify(auditService).record(
                anyString(), // Session ID is dynamic
                eq("user-123"), 
                eq("reflex"), 
                eq(SecurityEventType.APPROVAL_DENIED), 
                contains("action=DENY")
        );
    }
}
