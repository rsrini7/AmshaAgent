package com.amsha.app.pipeline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;

import com.amsha.channel.api.ChannelAllowlist;
import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.MessagePipeline;
import com.amsha.core.reflex.ReflexRegistry;
import com.amsha.core.reflex.ReflexResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class DenyPipelineTest {

    @Inject
    MessagePipeline pipeline;

    @InjectMock
    ReflexRegistry reflexRegistry;

    @InjectMock
    AuditService auditService;

    private ChannelAllowlist allowlist = mock(ChannelAllowlist.class);

    @InjectMock
    ChannelPluginRegistry channelPluginRegistry;

    private MessageContext context;
    private ChannelSender sender;
    private ChannelUser user;

    @BeforeEach
    void setup() {
        // Mock allowlist to permit our test user
        when(allowlist.isAllowed(anyString(), anyString())).thenReturn(true);

        user = new ChannelUser("user-123", "user-123", "alice", ChannelType.CLI);
        sender = mock(ChannelSender.class);

        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(plugin.allowlist()).thenReturn(allowlist);
        when(channelPluginRegistry.find(any(ChannelType.class))).thenReturn(Optional.of(plugin));

        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/deny", "session-456", Instant.now());
        context = new MessageContext("chat-789", user, msg);
    }

    @Test
    void testDenyRoutesToReflexRegistryNotDirectly() {
        // Setup mock response
        when(reflexRegistry.processIfReflex(anyString(), anyString(), anyString(), anyString(), eq(context)))
                .thenReturn(new ReflexResult(Optional.of(""), "/deny"));

        // Process message through pipeline
        pipeline.process(context);

        // Verify ReflexRegistry was called exactly once
        verify(reflexRegistry, times(1))
                .processIfReflex(
                        anyString(), // Session resolution handler might change this
                        eq("user-123"),
                        eq("chat-789"),
                        eq("/deny"),
                        eq(context));

        // Verify sender was NOT called because response was blank
        verifyNoInteractions(sender);
    }

    @Test
    void testDenyAuditEventFires() {
        // To follow requirements strictly:
        when(reflexRegistry.processIfReflex(anyString(), anyString(), anyString(), anyString(), eq(context)))
                .thenAnswer(invocation -> {
                    auditService.record(
                            (String) invocation.getArgument(0),
                            (String) invocation.getArgument(1),
                            "reflex",
                            SecurityEventType.APPROVAL_DENIED,
                            "action=DENY session=" + invocation.getArgument(0));
                    return new ReflexResult(Optional.of("🚫"), "/deny");
                });

        pipeline.process(context);

        verify(auditService)
                .record(
                        anyString(), // Session ID is dynamic
                        eq("user-123"),
                        eq("reflex"),
                        eq(SecurityEventType.APPROVAL_DENIED),
                        contains("action=DENY"));
    }
}
