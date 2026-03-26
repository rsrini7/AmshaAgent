package com.arcclaw.app.channel;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.channel.api.ChannelJobNotifier;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.cron.AgentJobCompleted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChannelCronNotificationDispatcherTest {

    @Mock
    Instance<ChannelJobNotifier> notifiers;

    @Mock
    ChannelJobNotifier telegramNotifier;

    @InjectMocks
    ChannelCronNotificationDispatcher dispatcher;



    @Test
    void testTelegramJobNotifierSelectedForTelegramEvent() {
        when(telegramNotifier.supports(ChannelType.TELEGRAM)).thenReturn(true);
        when(notifiers.stream()).thenReturn(Stream.of(telegramNotifier));

        AgentJobCompleted event = new AgentJobCompleted(
            "cron-1", "user-1", ChannelType.TELEGRAM, "chat-1", 
            "corr-1", "Hello", null);

        dispatcher.onJobCompleted(event);

        verify(telegramNotifier).notifyJobCompleted("chat-1", "Hello", "corr-1");
    }

    @Test
    void testUnknownChannelTypeLogsWarning() {
        when(telegramNotifier.supports(ChannelType.CLI)).thenReturn(false);
        when(notifiers.stream()).thenReturn(Stream.of(telegramNotifier));

        AgentJobCompleted event = new AgentJobCompleted(
            "cron-1", "user-1", ChannelType.CLI, "chat-1", 
            "corr-1", "Hello", null);

        dispatcher.onJobCompleted(event);

        // No exception thrown, and telegram notifier is not called
        verify(telegramNotifier, never()).notifyJobCompleted(anyString(), anyString(), anyString());
    }

    @Test
    void testNullChannelTypeIsIgnored() {
        AgentJobCompleted event = new AgentJobCompleted(
            "cron-1", "user-1", null, "chat-1", 
            "corr-1", "Hello", null);

        dispatcher.onJobCompleted(event);

        // Notifiers stream shouldn't even be called
        verify(telegramNotifier, never()).notifyJobCompleted(anyString(), anyString(), anyString());
    }
}
