package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.amsha.channel.api.hitl.ApprovalDecision;
import com.amsha.channel.api.hitl.ApprovalDecisionEvent;
import com.amsha.channel.api.hitl.ApprovalOutcome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelegramStaleApprovalObserverTest {

    @Mock
    TelegramChannelSender sender;

    @Mock
    TelegramMarkdownV2Renderer renderer;

    private TelegramStaleApprovalObserver observer;

    @BeforeEach
    void setUp() {
        observer = new TelegramStaleApprovalObserver();
        observer.sender = sender;
        observer.renderer = renderer;
        observer.hitlTimeoutHours = 24;
    }

    @Test
    void testNonExpiredIgnored() {
        ApprovalDecisionEvent event = new ApprovalDecisionEvent(
                "sess-1", "CLI", "123", "cmd", ApprovalDecision.APPROVE, ApprovalOutcome.APPROVED);
        assertDoesNotThrow(() -> observer.onApprovalDecision(event));
        verifyNoInteractions(sender);
    }

    @Test
    void testNonTelegramIgnored() {
        ApprovalDecisionEvent event = new ApprovalDecisionEvent(
                "sess-1", "CLI", "123", "cmd", ApprovalDecision.REJECT, ApprovalOutcome.EXPIRED);
        assertDoesNotThrow(() -> observer.onApprovalDecision(event));
        verifyNoInteractions(sender);
    }

    @Test
    void testTelegramExpiredHandled() {
        when(renderer.esc(anyString())).thenAnswer(inv -> inv.getArgument(0));

        ApprovalDecisionEvent event = new ApprovalDecisionEvent(
                "sess-1", "TELEGRAM", "12345", "cmd", ApprovalDecision.REJECT, ApprovalOutcome.EXPIRED);

        assertDoesNotThrow(() -> observer.onApprovalDecision(event));
        verify(sender).sendWithKeyboard(any(), contains("Your approval window for `cmd` has expired"), isNull());
    }
}
