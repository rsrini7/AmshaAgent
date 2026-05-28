package com.arcclaw.app.channel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.SessionContext;
import com.arcclaw.core.pipeline.handlers.AgentDispatchHandler;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AgentDispatchHandlerAutonomyTest {

    @Inject
    AgentDispatchHandler handler;

    @InjectMock
    AgentLoop agentLoop;

    @InjectMock
    ChannelPluginRegistry registry;

    @BeforeEach
    void setup() {
        when(registry.find(any(ChannelType.class))).thenReturn(Optional.empty());
    }

    @Test
    void telegramPipelinePreservesFULLAutonomyFromRestSession() throws Exception {
        ChannelUser user = new ChannelUser("user-full", "chat-full", "user-full", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "hello", "chat-full", Instant.now());
        MessageContext ctx = new MessageContext("chat-full", user, msg);
        ctx.setSessionId("session-full");
        ctx.setAutonomyLevel(AutonomyLevel.FULL);
        ctx.setSessionContext(new SessionContext("session-full", user, AutonomyLevel.FULL, false));

        AtomicReference<AutonomyLevel> captured = new AtomicReference<>();
        when(agentLoop.run(eq("session-full"), anyString(), anyString(), any(), anyInt(), eq(ctx)))
                .thenAnswer(inv -> {
                    captured.set(WorkspaceContext.AUTONOMY.get());
                    return AgentResult.finished("done", Collections.emptyList());
                });

        handler.handle(ctx);

        assertEquals(AutonomyLevel.FULL, captured.get());

        verify(agentLoop).run(eq("session-full"), anyString(), anyString(), any(), anyInt(), eq(ctx));
    }

    @Test
    void telegramPipelineDefaultsToSupervisedForUnknownSession() throws Exception {
        ChannelUser user =
                new ChannelUser("user-supervised", "chat-supervised", "user-supervised", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "hello", "chat-supervised", Instant.now());
        MessageContext ctx = new MessageContext("chat-supervised", user, msg);
        ctx.setSessionId("session-supervised");
        // SessionResolutionHandler now defaults null autonomy to SUPERVISED before dispatch
        ctx.setAutonomyLevel(AutonomyLevel.SUPERVISED);
        ctx.setSessionContext(new SessionContext("session-supervised", user, AutonomyLevel.SUPERVISED, false));

        AtomicReference<AutonomyLevel> captured = new AtomicReference<>();
        when(agentLoop.run(eq("session-supervised"), anyString(), anyString(), any(), anyInt(), eq(ctx)))
                .thenAnswer(inv -> {
                    captured.set(WorkspaceContext.AUTONOMY.get());
                    return AgentResult.finished("done", Collections.emptyList());
                });

        handler.handle(ctx);

        assertEquals(AutonomyLevel.SUPERVISED, captured.get());

        verify(agentLoop).run(eq("session-supervised"), anyString(), anyString(), any(), anyInt(), eq(ctx));
    }
}
