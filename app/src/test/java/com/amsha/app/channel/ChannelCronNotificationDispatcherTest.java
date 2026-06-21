package com.amsha.app.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.amsha.channel.api.ChannelJobNotifier;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelType;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.cron.AgentJobCompleted;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChannelCronNotificationDispatcherTest {

    @Mock
    ChannelPluginRegistry pluginRegistry;

    @Mock
    ChannelPlugin telegramPlugin;

    @Mock
    ChannelJobNotifier telegramNotifier;

    @InjectMocks
    ChannelCronNotificationDispatcher dispatcher;

    @Test
    void testTelegramJobNotifierSelectedForTelegramEvent() {
        when(telegramPlugin.jobNotifier()).thenReturn(telegramNotifier);
        when(pluginRegistry.find(ChannelType.TELEGRAM)).thenReturn(Optional.of(telegramPlugin));

        AgentJobCompleted event = new AgentJobCompleted(
                "cron-1", "user-1", ChannelType.TELEGRAM, "chat-1", "corr-1", "Hello", null, null, null);

        dispatcher.onJobCompleted(event);

        verify(telegramNotifier).notifyJobCompleted("chat-1", "Hello", "corr-1");
    }

    @Test
    void testUnknownChannelTypeLogsWarning() {
        when(pluginRegistry.find(ChannelType.CLI)).thenReturn(Optional.empty());

        AgentJobCompleted event = new AgentJobCompleted(
                "cron-1", "user-1", ChannelType.CLI, "chat-1", "corr-1", "Hello", null, null, null);

        dispatcher.onJobCompleted(event);

        // No exception thrown, and telegram notifier is not called
        verify(telegramNotifier, never()).notifyJobCompleted(any(), any(), any());
    }

    @Test
    void testNullChannelTypeIsIgnored() {
        AgentJobCompleted event =
                new AgentJobCompleted("cron-1", "user-1", null, "chat-1", "corr-1", "Hello", null, null, null);

        dispatcher.onJobCompleted(event);

        // Registry find should not even be called
        verify(pluginRegistry, never()).find(any(ChannelType.class));
    }
}
