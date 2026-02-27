package com.arcclaw.app.channel.telegram;

import com.arcclaw.app.security.TelegramAllowlistGuard;
import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.agent.RoleRegistry;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.User;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
public class TelegramAgentHandlerTest {

    @Inject
    TelegramAgentHandler handler;

    @InjectMock
    AgentLoop agentLoopMock;

    @InjectMock
    WorkspaceManager workspaceManagerMock;

    @InjectMock
    StatefulAgentStore statefulAgentStoreMock;

    @InjectMock
    ReasoningEngine reasoningEngineMock;

    @InjectMock
    IntentClassifierService intentClassifierMock;

    @InjectMock
    ReflexRegistry reflexRegistryMock;

    @InjectMock
    TelegramAllowlistGuard allowlistGuardMock;

    @InjectMock
    RoleRegistry roleRegistryMock;

    ProducerTemplate producerTemplateMock;

    ManagedExecutor executorMock;

    @BeforeEach
    void setup() {
        producerTemplateMock = mock(ProducerTemplate.class);
        executorMock = mock(ManagedExecutor.class);

        // Unwrap proxy to inject mocks into private fields
        TelegramAgentHandler instance = handler;
        if (handler instanceof io.quarkus.arc.ClientProxy) {
            instance = (TelegramAgentHandler) ((io.quarkus.arc.ClientProxy) handler).arc_contextualInstance();
        }
        instance.producerTemplate = producerTemplateMock;
        instance.executor = executorMock;

        // Force synchronous execution of the submitted runnable
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executorMock).submit(any(Runnable.class));

        // Default mock for intent classifier
        when(intentClassifierMock.classify(anyString())).thenReturn("coordinator");

        // Default mock for agent loop
        when(agentLoopMock.run(anyString(), anyString(), anyString(), any(ReasoningEngine.class), anyInt()))
                .thenReturn(AgentResult.finished("mock-answer", List.of()));

        // Default mock for reflex registry (simulate no commands hit, passthrough sanitized input)
        when(reflexRegistryMock.processIfReflex(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new com.arcclaw.core.reflex.ReflexResult(Optional.empty(), inv.getArgument(2)));

        // Default mock for allowlist guard
        when(allowlistGuardMock.isAllowed(any())).thenReturn(true);

        // Default mock for RoleRegistry
        when(roleRegistryMock.defaultMaxIterations(any())).thenReturn(5);
    }

    @Test
    void testReceiveMessage_Success_NewSession() {
        // Arrange
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        IncomingMessage msg = new IncomingMessage();
        msg.setText("hello");
        User from = new User();
        from.setId(123L);
        msg.setFrom(from);

        when(exchange.getIn()).thenReturn(in);
        when(in.getBody(IncomingMessage.class)).thenReturn(msg);
        when(in.getHeader("CamelTelegramChatId", String.class)).thenReturn("chat456");
        when(statefulAgentStoreMock.load(anyString(), anyString())).thenReturn(Optional.empty());

        // Act
        handler.receiveMessage(exchange);

        // Assert
        String expectedSessionId = UUID.nameUUIDFromBytes("chat456:123".getBytes(StandardCharsets.UTF_8)).toString();

        verify(intentClassifierMock).classify("hello");
        verify(roleRegistryMock).defaultMaxIterations(AgentRoleEnum.COORDINATOR);
        verify(agentLoopMock).run(eq(expectedSessionId), eq("coordinator"), eq("hello"), any(), eq(5));
        verify(producerTemplateMock).sendBodyAndHeaders(contains("telegram:bots"), eq("mock-answer"),
                eq(java.util.Map.of("CamelTelegramChatId", "chat456")));

        // Context should be cleared
        assertFalse(WorkspaceScopeHolder.isActive());
    }

    @Test
    void testReceiveMessage_StaleSession_WipesState() {
        // Arrange
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        IncomingMessage msg = new IncomingMessage();
        msg.setText("hello again");
        User from = new User();
        from.setId(123L);
        msg.setFrom(from);

        when(exchange.getIn()).thenReturn(in);
        when(in.getBody(IncomingMessage.class)).thenReturn(msg);
        when(in.getHeader("CamelTelegramChatId", String.class)).thenReturn("chat456");

        // Mock a COMPLETED checkpoint
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setId(1L);
        checkpoint.setStatus(CheckpointStatus.COMPLETED);
        String deterministicId = UUID.nameUUIDFromBytes("chat456:123".getBytes(StandardCharsets.UTF_8)).toString();

        when(intentClassifierMock.classify("hello again")).thenReturn("coordinator");
        when(statefulAgentStoreMock.load(eq(deterministicId), eq("coordinator"))).thenReturn(Optional.of(checkpoint));

        // Act
        handler.receiveMessage(exchange);

        // Assert
        verify(workspaceManagerMock, never()).terminateSession(anyString());
        verify(statefulAgentStoreMock)
                .save(argThat(cp -> cp.getStatus() == CheckpointStatus.IN_PROGRESS && "[]".equals(cp.getStateJson())));

        verify(roleRegistryMock).defaultMaxIterations(AgentRoleEnum.COORDINATOR);
        verify(agentLoopMock).run(eq(deterministicId), eq("coordinator"), eq("hello again"), any(), eq(5));
    }

    @Test
    void testReceiveMessage_NullMessage_Ignored() {
        // Arrange
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        when(exchange.getIn()).thenReturn(in);
        when(in.getBody(IncomingMessage.class)).thenReturn(null);

        // Act
        handler.receiveMessage(exchange);

        // Assert
        verify(executorMock, never()).submit(any(Runnable.class));
    }

    @Test
    void testReflexInterceptorPreventsAgentLoop() {
        // Arrange
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        IncomingMessage msg = new IncomingMessage();
        msg.setText("/status");
        User from = new User();
        from.setId(123L);
        msg.setFrom(from);
        when(exchange.getIn()).thenReturn(in);
        when(in.getBody(IncomingMessage.class)).thenReturn(msg);
        when(in.getHeader("CamelTelegramChatId", String.class)).thenReturn("chat456");

        // Mock ReflexRegistry to return a status response
        when(reflexRegistryMock.processIfReflex(anyString(), anyString(), eq("/status")))
                .thenReturn(new com.arcclaw.core.reflex.ReflexResult(Optional.of("📊 Status: COMPLETED | Cost: $0.05 | Session: abcd1234"), "/status"));

        // Act
        handler.receiveMessage(exchange);

        // Assert: AgentLoop must NOT be called
        verify(agentLoopMock, never()).run(any(), any(), any(), any(), anyInt());
        // Reply must be sent with reflex response
        verify(producerTemplateMock).sendBodyAndHeaders(
        contains("telegram:bots"),
        contains("📊 Status"),
        argThat(headers -> "chat456".equals(
                ((java.util.Map<?, ?>) headers).get("CamelTelegramChatId"))));
    }

    @Test
    void testReceiveMessage_UnauthorizedUser_BlocksAndReplies() {
        // Arrange
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        IncomingMessage msg = new IncomingMessage();
        msg.setText("hello");
        User from = new User();
        from.setId(999L);
        from.setUsername("hacker");
        msg.setFrom(from);

        when(exchange.getIn()).thenReturn(in);
        when(in.getBody(IncomingMessage.class)).thenReturn(msg);
        when(in.getHeader("CamelTelegramChatId", String.class)).thenReturn("chat999");

        when(allowlistGuardMock.isAllowed("hacker")).thenReturn(false);
        when(allowlistGuardMock.isSilentBlock()).thenReturn(false);
        when(allowlistGuardMock.getBlockResponse()).thenReturn("⛔ Access denied.");

        // Act
        handler.receiveMessage(exchange);

        // Assert
        verify(executorMock, never()).submit(any(Runnable.class));
        verify(producerTemplateMock).sendBodyAndHeaders(
                contains("telegram:bots"),
                eq("⛔ Access denied."),
                anyMap()
        );
    }
}
