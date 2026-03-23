package com.arcclaw.app.channel.telegram;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.arcclaw.core.pipeline.handlers.AgentDispatchHandler;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.scope.WorkspaceContext;

import java.util.Collections;
import java.time.Instant;

@QuarkusTest
class AgentDispatchHandlerAutonomyTest {

    @Inject
    AgentDispatchHandler handler;

    @InjectMock
    AgentLoop agentLoop;

    @Test
    void telegramPipelinePreservesFULLAutonomyFromRestSession() throws Exception {
        ChannelUser user = new ChannelUser("user-full", "chat-full", "user-full", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "hello", "chat-full", Instant.now());
        MessageContext ctx = new MessageContext("chat-full", user, msg, mock(ChannelSender.class));
        ctx.setSessionId("session-full");
        ctx.setAutonomyLevel(AutonomyLevel.FULL);
        ctx.setSessionContext(new com.arcclaw.core.pipeline.SessionContext(
            "session-full", user, AutonomyLevel.FULL, false));

        java.util.concurrent.atomic.AtomicReference<AutonomyLevel> captured = new java.util.concurrent.atomic.AtomicReference<>();
        when(agentLoop.run(eq("session-full"), anyString(), anyString(), any(), anyInt(), eq(ctx)))
                .thenAnswer(inv -> {
                    captured.set(WorkspaceContext.autonomy());
                    return AgentResult.finished("done", Collections.emptyList());
                });

        handler.handle(ctx);

        assertEquals(AutonomyLevel.FULL, captured.get());

        verify(agentLoop).run(eq("session-full"), anyString(), anyString(), any(), anyInt(), eq(ctx));
    }

    @Test
    void telegramPipelineDefaultsToSupervisedForUnknownSession() throws Exception {
        ChannelUser user = new ChannelUser("user-supervised", "chat-supervised", "user-supervised", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "hello", "chat-supervised", Instant.now());
        MessageContext ctx = new MessageContext("chat-supervised", user, msg, mock(ChannelSender.class));
        ctx.setSessionId("session-supervised");
        // Explicitly null autonomy
        ctx.setAutonomyLevel(null);
        ctx.setSessionContext(new com.arcclaw.core.pipeline.SessionContext(
            "session-supervised", user, null, false));

        java.util.concurrent.atomic.AtomicReference<AutonomyLevel> captured = new java.util.concurrent.atomic.AtomicReference<>();
        when(agentLoop.run(eq("session-supervised"), anyString(), anyString(), any(), anyInt(), eq(ctx)))
                .thenAnswer(inv -> {
                    captured.set(WorkspaceContext.autonomy());
                    return AgentResult.finished("done", Collections.emptyList());
                });

        handler.handle(ctx);

        assertEquals(AutonomyLevel.SUPERVISED, captured.get());

        verify(agentLoop).run(eq("session-supervised"), anyString(), anyString(), any(), anyInt(), eq(ctx));
    }
}
