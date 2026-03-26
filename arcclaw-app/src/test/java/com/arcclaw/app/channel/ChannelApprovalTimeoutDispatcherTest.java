package com.arcclaw.app.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.arcclaw.channel.api.ChannelJobNotifier;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.lifecycle.ApprovalTimedOutEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChannelApprovalTimeoutDispatcherTest {

    @Mock
    ChannelPluginRegistry pluginRegistry;

    @Mock
    ChannelPlugin telegramPlugin;

    @Mock
    ChannelJobNotifier telegramNotifier;

    @InjectMocks
    ChannelApprovalTimeoutDispatcher dispatcher;

    @Test
    void testTelegramTimeoutNotifiedCorrectly() {
        when(telegramPlugin.jobNotifier()).thenReturn(telegramNotifier);
        when(pluginRegistry.find(ChannelType.TELEGRAM)).thenReturn(Optional.of(telegramPlugin));

        ApprovalTimedOutEvent event = new ApprovalTimedOutEvent(
            "session-123", "chat-456", "TELEGRAM", "tool-foo", 10);

        dispatcher.onApprovalTimedOut(event);

        verify(telegramNotifier).notifyApprovalTimeout("chat-456", "session-123", "tool-foo", 10);
    }

    @Test
    void testUnknownChannelTypeDoesNotDefaultToTelegram() {
        // Event says "SLACK" (not supported)
        ApprovalTimedOutEvent event = new ApprovalTimedOutEvent(
            "session-123", "chat-456", "SLACK", "tool-foo", 10);

        dispatcher.onApprovalTimedOut(event);

        // Should not call telegram registry since it's not telegram
        verify(pluginRegistry, never()).find(ChannelType.TELEGRAM);
        // Should not call any notifier
        verify(telegramNotifier, never()).notifyApprovalTimeout(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void testBlankChannelPlatformIdIsIgnored() {
        ApprovalTimedOutEvent event = new ApprovalTimedOutEvent(
            "session-123", "", "TELEGRAM", "tool-foo", 10);

        dispatcher.onApprovalTimedOut(event);

        verify(pluginRegistry, never()).find(any(ChannelType.class));
    }
}
