package com.amsha.channel.telegram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.amsha.channel.api.hitl.ApprovalDecision;
import com.amsha.channel.api.hitl.ApprovalGateway;
import com.amsha.channel.api.hitl.ApprovalOutcome;
import com.amsha.channel.api.hitl.ApprovalResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.telegram.model.Chat;
import org.apache.camel.component.telegram.model.IncomingCallbackQuery;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.SendChatActionMessage;
import org.apache.camel.component.telegram.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the live-progress behaviour added for observation #3: after a tool is approved the
 * handler starts a background tracker (via {@link ScheduledExecutorService}) that consults the
 * resume state while the approved run executes. The tracker is run synchronously here (the mock
 * scheduler executes the scheduled {@link Runnable} inline) so its side effects are observable
 * without blocking threads.
 *
 * The resume state is queried through the channel-api SPI {@link ApprovalGateway#isSessionResuming}
 * - never the core {@code StatefulAgentStore} directly - to respect the ArchUnit boundary that
 * keeps channel modules independent of {@code com.amsha.core.state} internals.
 */
class TelegramCallbackHandlerProgressTest {

    private TelegramCallbackHandler handler;
    private TelegramChannelSender sender;
    private ApprovalGateway approvalGateway;
    private ScheduledExecutorService progressScheduler;

    @BeforeEach
    void setUp() {
        handler = new TelegramCallbackHandler();
        sender = mock(TelegramChannelSender.class);
        approvalGateway = mock(ApprovalGateway.class);
        progressScheduler = mock(ScheduledExecutorService.class);
        handler.sender = sender;
        handler.approvalGateway = approvalGateway;
        handler.progressScheduler = progressScheduler;
        handler.botToken = "dummy-token";
        handler.objectMapper = new ObjectMapper();
        when(approvalGateway.resolveSessionIdPrefix(anyString())).thenAnswer(i -> i.getArgument(0));
        // Execute the scheduled task inline so we can assert its behaviour synchronously.
        when(progressScheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(0).run();
                    return mock(ScheduledFuture.class);
                });
    }

    private Exchange approvedExchange() {
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
        return exchange;
    }

    @Test
    void progressTrackerStartsAndFinalizesApprovedWhenRunAlreadyDone() {
        when(approvalGateway.decide(anyString(), eq("tool1"), any(ApprovalDecision.class), anyString()))
                .thenReturn(new ApprovalResult(ApprovalOutcome.APPROVED, "sess-1", "tool1", 0, List.of()));
        // Resumed run already finished -> tracker checks status once and cancels without sending typing action.
        when(approvalGateway.isSessionResuming("sess-1")).thenReturn(false);

        handler.handleCallback(approvedExchange());

        // The live-progress tracker was scheduled non-blockingly...
        verify(progressScheduler).scheduleWithFixedDelay(any(Runnable.class), eq(2L), eq(4L), eq(TimeUnit.SECONDS));
        // ...it consulted the resume state (via the channel-api SPI) to decide whether to keep
        // sending progress...
        verify(approvalGateway).isSessionResuming("sess-1");
        verify(sender, never()).sendChatAction(anyString(), any());
        // ...and the approval message was finalized to the "Approved" confirmation.
        verify(sender, atLeastOnce()).editMessage(eq("chat-1"), eq(100), contains("Approved"), eq(null));
    }

    @Test
    void progressTrackerSendsChatActionWhileSessionResuming() {
        when(approvalGateway.decide(anyString(), eq("tool1"), any(ApprovalDecision.class), anyString()))
                .thenReturn(new ApprovalResult(ApprovalOutcome.APPROVED, "sess-1", "tool1", 0, List.of()));
        when(approvalGateway.isSessionResuming("sess-1")).thenReturn(true);

        handler.handleCallback(approvedExchange());

        verify(progressScheduler).scheduleWithFixedDelay(any(Runnable.class), eq(2L), eq(4L), eq(TimeUnit.SECONDS));
        verify(approvalGateway).isSessionResuming("sess-1");
        verify(sender).sendChatAction(eq("chat-1"), eq(SendChatActionMessage.Action.TYPING));
    }

    /**
     * The gateway answers with the session that actually owns the approved checkpoint, which for
     * delegated work is the ":delegate:" child. Tracking the root instead would see
     * {@code isSessionResuming == false} and drop the typing heartbeat immediately.
     */
    @Test
    void progressTrackerFollowsDelegatedSessionReturnedByGateway() {
        when(approvalGateway.decide(anyString(), eq("tool1"), any(ApprovalDecision.class), anyString()))
                .thenReturn(
                        new ApprovalResult(ApprovalOutcome.APPROVED, "sess-1:delegate:coder:1", "tool1", 0, List.of()));
        when(approvalGateway.isSessionResuming("sess-1:delegate:coder:1")).thenReturn(false);

        handler.handleCallback(approvedExchange());

        verify(approvalGateway).isSessionResuming("sess-1:delegate:coder:1");
        verify(approvalGateway, never()).isSessionResuming("sess-1");
    }

    /** "✅ Approved" is written once; a duplicate edit would make Telegram reject it. */
    @Test
    void approvedMessageIsEditedExactlyOnce() {
        when(approvalGateway.decide(anyString(), eq("tool1"), any(ApprovalDecision.class), anyString()))
                .thenReturn(new ApprovalResult(ApprovalOutcome.APPROVED, "sess-1", "tool1", 0, List.of()));
        when(approvalGateway.isSessionResuming("sess-1")).thenReturn(false);

        handler.handleCallback(approvedExchange());

        verify(sender, times(1)).editMessage(eq("chat-1"), eq(100), contains("✅ Approved"), eq(null));
    }
}
