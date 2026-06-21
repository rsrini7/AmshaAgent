package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.message.UserMessages;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.ManagedContext;

@ExtendWith(MockitoExtension.class)
class ListCronsReflexTest {

    @Mock
    CronStorePort cronStore;

    @InjectMocks
    ListCronsReflex reflex;

    @BeforeEach
    void setRetentionHours() throws Exception {
        Field field = ListCronsReflex.class.getDeclaredField("retentionHours");
        field.setAccessible(true);
        field.setInt(reflex, 24);
    }

    private MessageContext context() {
        ChannelUser user = new ChannelUser("user1", "chat1", "User 1", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/crons", "sess-1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void matchesRecognizesCronCommands() {
        assertTrue(reflex.matches("/cron"));
        assertTrue(reflex.matches("/crons"));
        assertTrue(reflex.matches("/list crons"));
        assertTrue(reflex.matches("list crons"));
        assertTrue(reflex.matches("list tasks"));
        assertTrue(reflex.matches("show cron"));
        assertTrue(reflex.matches("my crons"));
        assertTrue(reflex.matches("list my crons"));
    }

    @Test
    void matchesRecognizesPrefixCommands() {
        assertTrue(reflex.matches("/jobs all"));
        assertTrue(reflex.matches("/schedule list"));
        assertTrue(reflex.matches("list my cron jobs"));
    }

    @Test
    void rejectsUnrelatedInput() {
        assertFalse(reflex.matches("/status"));
        assertFalse(reflex.matches("cron"));
        assertFalse(reflex.matches(null));
    }

    @Test
    void executeReturnsContextNotAvailableWhenScopeNotBound() {
        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/crons", context());
        assertTrue(result.commandResponse().isPresent());
        assertEquals(
                UserMessages.LIST_CONTEXT_NOT_AVAILABLE,
                result.commandResponse().get());
    }

    @Test
    void executeReturnsNoScheduleWhenEmpty() {
        try (MockedStatic<Arc> arcStatic = mockStatic(Arc.class)) {
            ArcContainer mockContainer = mock(ArcContainer.class);
            ManagedContext mockContext = mock(ManagedContext.class);
            arcStatic.when(Arc::container).thenReturn(mockContainer);
            when(mockContainer.requestContext()).thenReturn(mockContext);
            when(mockContext.isActive()).thenReturn(false);

            when(cronStore.findByUserId("user1")).thenReturn(List.of());

            ReflexResult[] resultHolder = new ReflexResult[1];
            WorkspaceScopeHolder.run("sess-1", "user1", "chat1", () -> {
                resultHolder[0] = reflex.execute("sess-1", "user1", "chat1", "/crons", context());
            });

            assertTrue(resultHolder[0].commandResponse().isPresent());
            assertEquals(
                    UserMessages.NO_SCHEDULE_FOUND,
                    resultHolder[0].commandResponse().get());
        }
    }

    @Test
    void executeDisplaysActiveCronJobs() {
        try (MockedStatic<Arc> arcStatic = mockStatic(Arc.class)) {
            ArcContainer mockContainer = mock(ArcContainer.class);
            ManagedContext mockContext = mock(ManagedContext.class);
            arcStatic.when(Arc::container).thenReturn(mockContainer);
            when(mockContainer.requestContext()).thenReturn(mockContext);
            when(mockContext.isActive()).thenReturn(false);

            AgentCronDto dto = new AgentCronDto(
                    "cron-1",
                    "sess-1",
                    "user1",
                    "chat1",
                    "CLI",
                    "platform",
                    "0 9 * * *",
                    "UTC",
                    false,
                    "Run daily report",
                    Instant.now().plusSeconds(3600),
                    null,
                    "ACTIVE",
                    null,
                    null,
                    null,
                    Instant.now(),
                    Instant.now());
            when(cronStore.findByUserId("user1")).thenReturn(List.of(dto));

            ReflexResult[] resultHolder = new ReflexResult[1];
            WorkspaceScopeHolder.run("sess-1", "user1", "chat1", () -> {
                resultHolder[0] = reflex.execute("sess-1", "user1", "chat1", "/crons", context());
            });

            assertTrue(resultHolder[0].commandResponse().isPresent());
            String output = resultHolder[0].commandResponse().get();
            assertTrue(output.contains("cron-1"));
            assertTrue(output.contains("0 9 * * *"));
        }
    }

    @Test
    void executeHidesOldTerminalJobs() {
        try (MockedStatic<Arc> arcStatic = mockStatic(Arc.class)) {
            ArcContainer mockContainer = mock(ArcContainer.class);
            ManagedContext mockContext = mock(ManagedContext.class);
            arcStatic.when(Arc::container).thenReturn(mockContainer);
            when(mockContainer.requestContext()).thenReturn(mockContext);
            when(mockContext.isActive()).thenReturn(false);

            // One active job, one old completed job
            AgentCronDto active = new AgentCronDto(
                    "cron-1",
                    "sess-1",
                    "user1",
                    "chat1",
                    "CLI",
                    "platform",
                    "0 9 * * *",
                    "UTC",
                    false,
                    "Active job",
                    Instant.now().plusSeconds(3600),
                    null,
                    "ACTIVE",
                    null,
                    null,
                    null,
                    Instant.now(),
                    Instant.now());
            AgentCronDto oldCompleted = new AgentCronDto(
                    "cron-2",
                    "sess-1",
                    "user1",
                    "chat1",
                    "CLI",
                    "platform",
                    "0 10 * * *",
                    "UTC",
                    true,
                    "Old job",
                    null,
                    Instant.now().minusSeconds(86400 * 2),
                    "COMPLETED",
                    null,
                    null,
                    null,
                    Instant.now().minusSeconds(86400 * 3),
                    Instant.now().minusSeconds(86400 * 2));
            when(cronStore.findByUserId("user1")).thenReturn(List.of(active, oldCompleted));

            ReflexResult[] resultHolder = new ReflexResult[1];
            WorkspaceScopeHolder.run("sess-1", "user1", "chat1", () -> {
                resultHolder[0] = reflex.execute("sess-1", "user1", "chat1", "/crons", context());
            });

            String output = resultHolder[0].commandResponse().get();
            assertTrue(output.contains("cron-1"), "Active job should be shown");
            assertTrue(output.contains("hidden"), "Old terminal jobs should be hidden");
        }
    }
}
