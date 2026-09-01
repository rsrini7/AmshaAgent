package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.amsha.channel.api.hitl.ApprovalGateway;
import com.amsha.channel.api.hitl.ApprovalNotifier;
import com.amsha.channel.api.hitl.ApprovalOutcome;
import com.amsha.channel.api.hitl.ApprovalResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.telegram.model.Chat;
import org.apache.camel.component.telegram.model.IncomingCallbackQuery;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelegramCallbackHandlerTest {

    @Mock
    TelegramChannelSender sender;

    @Mock
    ApprovalGateway approvalGateway;

    @Mock
    ApprovalNotifier notificationService;

    private TelegramCallbackHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TelegramCallbackHandler();
        handler.sender = sender;
        handler.approvalGateway = approvalGateway;
        handler.notificationService = notificationService;
        handler.objectMapper = new ObjectMapper();
        handler.botToken = "dummy-token";

        lenient().when(approvalGateway.resolveSessionIdPrefix(anyString())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void testHandleCallbackNullQuery() {
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        when(exchange.getIn()).thenReturn(in);
        when(in.getBody(IncomingCallbackQuery.class)).thenReturn(null);

        assertDoesNotThrow(() -> handler.handleCallback(exchange));
    }

    @Test
    void testHandleCallbackApproveTool() {
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        when(exchange.getIn()).thenReturn(in);

        IncomingCallbackQuery query = new IncomingCallbackQuery();
        query.setId("cb-123");
        query.setData("A|sess-1|tool1");

        IncomingMessage msg = new IncomingMessage();
        msg.setMessageId(100L);
        Chat chat = new Chat();
        chat.setId("chat-1");
        msg.setChat(chat);
        msg.setText("pending approval");
        query.setMessage(msg);

        User from = new User();
        from.setId(555L);
        query.setFrom(from);

        when(in.getBody(IncomingCallbackQuery.class)).thenReturn(query);

        ApprovalResult result = new ApprovalResult(ApprovalOutcome.APPROVED, "sess-1", "tool1", 0, List.of());
        when(approvalGateway.decide(anyString(), eq("tool1"), any(), anyString()))
                .thenReturn(result);

        assertDoesNotThrow(() -> handler.handleCallback(exchange));
        verify(sender).editMessage(eq("chat-1"), eq(100), contains("⏳"), isNull());
        verify(sender).editMessage(eq("chat-1"), eq(100), contains("Approved"), isNull());
        verify(notificationService).flushPendingNotifications("chat-1");
    }

    @Test
    void testHandleCallbackDenyTool() {
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        when(exchange.getIn()).thenReturn(in);

        IncomingCallbackQuery query = new IncomingCallbackQuery();
        query.setId("cb-456");
        query.setData("D|sess-2|tool2");

        IncomingMessage msg = new IncomingMessage();
        msg.setMessageId(200L);
        Chat chat = new Chat();
        chat.setId("chat-2");
        msg.setChat(chat);
        msg.setText("pending approval");
        query.setMessage(msg);

        User from = new User();
        from.setId(666L);
        query.setFrom(from);

        when(in.getBody(IncomingCallbackQuery.class)).thenReturn(query);

        ApprovalResult result = new ApprovalResult(ApprovalOutcome.REJECTED, "sess-2", "tool2", 0, List.of());
        when(approvalGateway.decide(anyString(), eq("tool2"), any(), anyString()))
                .thenReturn(result);

        assertDoesNotThrow(() -> handler.handleCallback(exchange));
        verify(sender).editMessage(eq("chat-2"), eq(200), contains("⏳"), isNull());
        verify(sender).editMessage(eq("chat-2"), eq(200), contains("Rejected"), isNull());
    }
}
