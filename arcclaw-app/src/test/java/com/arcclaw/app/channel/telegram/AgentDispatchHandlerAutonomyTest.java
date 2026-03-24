package com.arcclaw.app.channel.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.SessionContext;
import com.arcclaw.core.pipeline.handlers.AgentDispatchHandler;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

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
        ctx.setSessionContext(new SessionContext(
            "session-full", user, AutonomyLevel.FULL, false));

        AtomicReference<AutonomyLevel> captured = new AtomicReference<>();
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
        ctx.setSessionContext(new SessionContext(
            "session-supervised", user, null, false));

        AtomicReference<AutonomyLevel> captured = new AtomicReference<>();
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
