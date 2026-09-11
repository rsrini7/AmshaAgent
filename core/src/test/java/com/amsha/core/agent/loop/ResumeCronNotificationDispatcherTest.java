package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.channel.api.ChannelJobNotifier;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelType;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.cron.AgentJobCompleted;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResumeCronNotificationDispatcherTest {

    private static final String JOB_ID = "12345678-1234-1234-1234-123456789012";
    private static final String DELIVERY_ID = "87654321-4321-4321-4321-210987654321";
    private static final String CRON_SESSION_ID = "cron-" + JOB_ID + "-" + DELIVERY_ID;

    private CronStorePort cronStore;
    private ChannelPluginRegistry pluginRegistry;
    private DomainEventBus eventBus;
    private ResumeCronNotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        cronStore = mock(CronStorePort.class);
        pluginRegistry = mock(ChannelPluginRegistry.class);
        eventBus = mock(DomainEventBus.class);
        dispatcher = new ResumeCronNotificationDispatcher(cronStore, pluginRegistry, eventBus);
    }

    private AgentCronDto createCronDto(String channelType, String platformId) {
        return new AgentCronDto(
                JOB_ID,
                "sess-1",
                "usr-1",
                "chat-1",
                channelType,
                platformId,
                "* * * * *",
                "UTC",
                true,
                "write python factorial",
                Instant.now(),
                null,
                CronStatus.RUNNING.name(),
                "coder",
                null,
                DELIVERY_ID,
                Instant.now(),
                Instant.now());
    }

    @Test
    void testIsCronSession() {
        assertFalse(ResumeCronNotificationDispatcher.isCronSession(null));
        assertFalse(ResumeCronNotificationDispatcher.isCronSession("cli-12345"));
        assertFalse(ResumeCronNotificationDispatcher.isCronSession("telegram-12345"));
        assertTrue(ResumeCronNotificationDispatcher.isCronSession("cron-" + JOB_ID));
    }

    @Test
    void testDispatchNonCronSessionReturnsFalse() {
        assertFalse(dispatcher.dispatch("cli-12345", AgentResult.finished("ok", List.of())));
        verifyNoInteractions(cronStore, pluginRegistry, eventBus);
    }

    @Test
    void testDispatchMalformedCronSessionReturnsFalse() {
        assertFalse(dispatcher.dispatch("cron-short", AgentResult.finished("ok", List.of())));
        verifyNoInteractions(cronStore, pluginRegistry, eventBus);
    }

    @Test
    void testDispatchJobNotFoundReturnsFalse() {
        when(cronStore.findById(JOB_ID)).thenReturn(Optional.empty());
        assertFalse(dispatcher.dispatch(CRON_SESSION_ID, AgentResult.finished("ok", List.of())));
        verify(cronStore).findById(JOB_ID);
        verifyNoInteractions(eventBus);
    }

    @Test
    void testDispatchAwaitingApprovalPublishesEvent() {
        when(cronStore.findById(JOB_ID)).thenReturn(Optional.of(createCronDto("cli", "cli")));

        AgentResult result =
                AgentResult.awaitingApproval("sandbox_exec", "{\"code\":\"print(1)\"}", CRON_SESSION_ID, List.of());

        boolean dispatched = dispatcher.dispatch(CRON_SESSION_ID, result);
        assertTrue(dispatched);

        ArgumentCaptor<AgentJobCompleted> captor = ArgumentCaptor.forClass(AgentJobCompleted.class);
        verify(eventBus).publish(eq(EventTypeCatalog.AGENT_JOB_COMPLETED), captor.capture());

        AgentJobCompleted event = captor.getValue();
        assertEquals(JOB_ID, event.cronId());
        assertEquals(ChannelType.CLI, event.channelType());
        assertEquals("sandbox_exec", event.toolName());
        assertTrue(event.renderedAnswer().contains("Approval Required for background task"));
        assertTrue(event.renderedAnswer().contains("sandbox_exec"));
        assertTrue(event.renderedAnswer().contains("/approve " + JOB_ID));
    }

    @Test
    void testDispatchFinishedPublishesEvent() {
        when(cronStore.findById(JOB_ID)).thenReturn(Optional.of(createCronDto("telegram", "chat-123")));

        AgentResult result = AgentResult.finished("The factorial is 3628800", List.of());

        boolean dispatched = dispatcher.dispatch(CRON_SESSION_ID, result);
        assertTrue(dispatched);

        ArgumentCaptor<AgentJobCompleted> captor = ArgumentCaptor.forClass(AgentJobCompleted.class);
        verify(eventBus).publish(eq(EventTypeCatalog.AGENT_JOB_COMPLETED), captor.capture());

        AgentJobCompleted event = captor.getValue();
        assertEquals(JOB_ID, event.cronId());
        assertEquals(ChannelType.TELEGRAM, event.channelType());
        assertNull(event.toolName());
        assertEquals("The factorial is 3628800", event.renderedAnswer());
    }

    @Test
    void testDispatchFallbackToPluginRegistryDirectNotifier() {
        dispatcher = new ResumeCronNotificationDispatcher(cronStore, pluginRegistry, null);

        when(cronStore.findById(JOB_ID)).thenReturn(Optional.of(createCronDto("cli", "cli")));
        ChannelPlugin plugin = mock(ChannelPlugin.class);
        ChannelJobNotifier notifier = mock(ChannelJobNotifier.class);
        when(plugin.jobNotifier()).thenReturn(notifier);
        when(pluginRegistry.find(ChannelType.CLI)).thenReturn(Optional.of(plugin));

        AgentResult result = AgentResult.awaitingApproval("sandbox_exec", "{\"n\":10}", CRON_SESSION_ID, List.of());

        boolean dispatched = dispatcher.dispatch(CRON_SESSION_ID, result);
        assertTrue(dispatched);

        verify(notifier).notifyApprovalRequired(eq("cli"), eq(CRON_SESSION_ID), eq("sandbox_exec"), anyString());
    }
}
