package com.arcclaw.core.pipeline.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.OngoingStubbing;

class CommandDispatchHandlerTest {

    private CommandDispatchHandler handler;
    private ReflexRegistry reflexRegistry;
    private ChannelSender sender;
    private ChannelPluginRegistry channelPluginRegistry;
    private MockedStatic<WorkspaceContext> mockedContext;

    @BeforeEach
    void setup() {
        reflexRegistry = mock(ReflexRegistry.class);
        sender = mock(ChannelSender.class);
        channelPluginRegistry = mock(ChannelPluginRegistry.class);
        
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        when(plugin.sender()).thenReturn(sender);
        when(channelPluginRegistry.find(any(ChannelType.class))).thenReturn(Optional.of(plugin));

        handler = new CommandDispatchHandler();
        handler.reflexRegistry = reflexRegistry;
        handler.channelPluginRegistry = channelPluginRegistry;
        handler.defaultTimezone = "Asia/Kolkata";
        mockedContext = mockStatic(WorkspaceContext.class);
        
        @SuppressWarnings("unchecked")
        OngoingStubbing<Object> stub = mockedContext
            .when(() -> WorkspaceContext.run(
                anyString(), anyString(), anyString(),
                any(AutonomyLevel.class), anyString(), any(Callable.class)
            ));

        stub.thenAnswer(inv -> {
            Callable<?> task = inv.getArgument(5);
            return task.call();
        });
    }

    @AfterEach
    void tearDown() {
        mockedContext.close();
    }

    @Test
    void testApproveWithCronIdStopsPipeline() throws Exception {
        ChannelUser user = new ChannelUser("1", "1", "user", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve cron123", "sid", Instant.now());
        MessageContext ctx = createCtx(user, msg);
        ctx.setSessionId("test-session");
        
        when(reflexRegistry.processIfReflex(anyString(), eq("1"), anyString(), eq("/approve cron123"), any()))
                .thenReturn(new ReflexResult(Optional.of("Approving cron task..."), "/approve cron123"));

        assertEquals(HandleResult.STOP, handler.handle(ctx));

        verify(sender).sendRaw(user, "Approving cron task...");
    }

    @Test
    void testApproveWithoutCronIdStopsPipeline() throws Exception {
        ChannelUser user = new ChannelUser("1", "1", "user", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve", "sid", Instant.now());
        MessageContext ctx = createCtx(user, msg);
        ctx.setSessionId("test-session");
        
        when(reflexRegistry.processIfReflex(anyString(), eq("1"), anyString(), eq("/approve"), any()))
                .thenReturn(new ReflexResult(Optional.of("Approving session..."), "/approve"));

        assertEquals(HandleResult.STOP, handler.handle(ctx));

        verify(sender).sendRaw(user, "Approving session...");
    }

    @Test
    void testTextPassesThrough() {
        ChannelUser user = new ChannelUser("1", "1", "user", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "Hello world", "sid", Instant.now());
        MessageContext ctx = createCtx(user, msg);

        assertEquals(HandleResult.CONTINUE, handler.handle(ctx));
        verify(sender, never()).sendRaw(any(), any());
    }

    @Test
    void testDenyStopsPipeline() {
        ChannelUser user = new ChannelUser("1", "1", "user", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/deny", "sid", Instant.now());
        MessageContext ctx = createCtx(user, msg);

        when(reflexRegistry.processIfReflex(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new ReflexResult(Optional.empty(), "/deny"));

        assertEquals(HandleResult.STOP, handler.handle(ctx));
        verify(sender, never()).sendRaw(any(), any());
    }

    @Test
    void testDispatchExceptionSendsErrorToUser() {
        ChannelUser user = new ChannelUser("1", "1", "user", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve", "sid", Instant.now());
        MessageContext ctx = createCtx(user, msg);

        // Mock reflexRegistry to throw RuntimeException
        when(reflexRegistry.processIfReflex(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("Something went wrong"));

        assertEquals(HandleResult.STOP, handler.handle(ctx));
        verify(sender).sendRaw(user, "⚠️ Command failed due to an internal error. Please try again.");
    }

    private MessageContext createCtx(ChannelUser user, ChannelMessage msg) {
        return new MessageContext("chat1", user, msg);
    }
}
