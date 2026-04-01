package com.arcclaw.channel.telegram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.reflex.AgentReflex;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.state.StatefulAgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.telegram.model.Chat;
import org.apache.camel.component.telegram.model.IncomingCallbackQuery;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramCallbackHandlerExpiryGuardTest {

    private TelegramCallbackHandler handler;
    private TelegramChannelSender sender;
    private StatefulAgentStore agentStore;
    private AgentReflex reflex;
    private Exchange exchange;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        handler = new TelegramCallbackHandler();
        sender = mock(TelegramChannelSender.class);
        agentStore = mock(StatefulAgentStore.class);
        reflex = mock(AgentReflex.class);
        exchange = mock(Exchange.class);

        Instance<AgentReflex> reflexes = mock(Instance.class);
        when(reflexes.iterator()).thenAnswer(inv -> List.of(reflex).iterator());

        handler.sender = sender;
        handler.reflexes = reflexes;
        handler.agentStore = agentStore;
        handler.objectMapper = new ObjectMapper();
        handler.botToken = "dummy-token"; // To prevent NPE on http client creation logging
        handler.hitlTimeoutHours = 24L;

        // Stub exchange
        Message message = mock(Message.class);
        when(exchange.getIn()).thenReturn(message);

        // Stub valid incoming callback for BATCH_APPROVE
        IncomingCallbackQuery query = new IncomingCallbackQuery();
        query.setId("cb-123");
        query.setData("BATCH_APPROVE|test-session|dummy_tool");

        IncomingMessage tgMessage = new IncomingMessage();
        tgMessage.setMessageId(101L);
        Chat chat = new Chat();
        chat.setId("1001");
        tgMessage.setChat(chat);
        query.setMessage(tgMessage);

        User user = new User();
        user.setId(2002L); // Change back to string if needed, assuming User is still Long
        query.setFrom(user);

        when(message.getBody(IncomingCallbackQuery.class)).thenReturn(query);
    }

    @Test
    void isCheckpointExpired_true_rejectsCall() throws Exception {
        // mock expired
        when(agentStore.isCheckpointExpired("test-session", 24L)).thenReturn(true);
        when(reflex.matches(anyString())).thenReturn(true);

        handler.handleCallback(exchange);

        // Verify reflex was NOT called
        verify(reflex, never()).execute(anyString(), anyString(), anyString(), anyString(), any());
        
        // Verify user is notified
        verify(sender).sendRaw(any(ChannelUser.class), eq("⏱ This approval request has expired. Please start a new request."));
    }

    @Test
    void isCheckpointExpired_false_proceedsToReflex() throws Exception {
        // mock not expired
        when(agentStore.isCheckpointExpired("test-session", 24L)).thenReturn(false);
        when(reflex.matches(anyString())).thenReturn(true);
        when(reflex.execute(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new ReflexResult(Optional.empty(), "dummy input"));

        handler.handleCallback(exchange);

        // Verify reflex WAS called
        verify(reflex).execute(eq("test-session"), eq("2002"), eq("1001"), eq("/approve dummy_tool"), any());

        // Verify no expiry message was sent
        verify(sender, never()).sendRaw(any(ChannelUser.class), eq("⏱ This approval request has expired. Please start a new request."));
    }
}
