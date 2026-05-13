package com.arcclaw.channel.telegram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.hitl.ApprovalDecision;
import com.arcclaw.channel.api.hitl.ApprovalGateway;
import com.arcclaw.channel.api.hitl.ApprovalOutcome;
import com.arcclaw.channel.api.hitl.ApprovalResult;
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
    private ApprovalGateway approvalGateway;
    private Exchange exchange;

    @BeforeEach
    void setup() {
        handler = new TelegramCallbackHandler();
        sender = mock(TelegramChannelSender.class);
        approvalGateway = mock(ApprovalGateway.class);
        exchange = mock(Exchange.class);

        handler.sender = sender;
        handler.approvalGateway = approvalGateway;
        handler.botToken = "dummy-token"; // To prevent NPE on http client creation logging
        handler.objectMapper = new ObjectMapper();

        // Stub exchange
        Message message = mock(Message.class);
        when(exchange.getIn()).thenReturn(message);

        // Stub resolution
        when(approvalGateway.resolveSessionIdPrefix(anyString())).thenAnswer(i -> i.getArgument(0));

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
        user.setId(2002L);
        query.setFrom(user);

        when(message.getBody(IncomingCallbackQuery.class)).thenReturn(query);
    }

    @Test
    void isCheckpointExpired_true_rejectsCall() {
        // mock expired
        when(approvalGateway.decide(eq("test-session"), eq("dummy_tool"), any(ApprovalDecision.class), anyString()))
            .thenReturn(new ApprovalResult(ApprovalOutcome.EXPIRED, "test-session", "dummy_tool", 0, List.of()));

        handler.handleCallback(exchange);

        // Verify keyboard is cleared and message shows result
        verify(sender).editMessage(eq("1001"), eq(101), eq("❌ Rejected: dummy_tool"), eq(null));
        // Verify no message was sent (A3: uses toast instead)
        verify(sender, never()).sendRaw(any(ChannelUser.class), anyString());
    }

    @Test
    void isCheckpointExpired_false_proceedsToApprove() {
        // mock not expired
        when(approvalGateway.decide(eq("test-session"), eq("dummy_tool"), any(ApprovalDecision.class), anyString()))
            .thenReturn(new ApprovalResult(ApprovalOutcome.APPROVED, "test-session", "dummy_tool", 0, List.of()));

        handler.handleCallback(exchange);

        // Verify keyboard is cleared and message shows result
        verify(sender).editMessage(eq("1001"), eq(101), eq("✅ Approved: dummy_tool"), eq(null));
        // Verify no message was sent (A3: uses toast instead)
        verify(sender, never()).sendRaw(any(ChannelUser.class), anyString());
    }
}
