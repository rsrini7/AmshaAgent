package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.message.UserMessages;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.persistence.api.CronStorePort;

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
class DeleteCronReflexTest {

    @Mock
    CronStorePort cronStore;

    @Mock
    AuditService auditService;

    @InjectMocks
    DeleteCronReflex reflex;

    private static final String VALID_UUID = "12345678-1234-1234-1234-123456789abc";

    private MessageContext context(String input) {
        ChannelUser user = new ChannelUser("user1", "chat1", "User 1", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, "sess-1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void matchesRecognizesDeleteCronVariants() {
        assertTrue(reflex.matches("deletecron " + VALID_UUID));
        assertTrue(reflex.matches("/deletecron " + VALID_UUID));
        assertTrue(reflex.matches("/cron delete " + VALID_UUID));
    }

    @Test
    void rejectsUnrelatedInput() {
        assertFalse(reflex.matches("/crons"));
        assertFalse(reflex.matches("delete something"));
        assertFalse(reflex.matches(null));
    }

    @Test
    void executeReturnsContextNotAvailableWhenScopeNotBound() {
        ReflexResult result = reflex.execute(
                "sess-1", "user1", "chat1", "deletecron " + VALID_UUID, context("deletecron " + VALID_UUID));
        assertTrue(result.commandResponse().isPresent());
        assertEquals(
                UserMessages.DELETE_CONTEXT_NOT_AVAILABLE,
                result.commandResponse().get());
    }

    @Test
    void executeReturnsUsageWhenIdBlank() {
        try (MockedStatic<Arc> arcStatic = mockStatic(Arc.class)) {
            ArcContainer mockContainer = mock(ArcContainer.class);
            ManagedContext mockContext = mock(ManagedContext.class);
            arcStatic.when(Arc::container).thenReturn(mockContainer);
            when(mockContainer.requestContext()).thenReturn(mockContext);
            when(mockContext.isActive()).thenReturn(false);

            ReflexResult[] resultHolder = new ReflexResult[1];
            WorkspaceScopeHolder.run("sess-1", "user1", "chat1", () -> {
                resultHolder[0] = reflex.execute("sess-1", "user1", "chat1", "deletecron", context("deletecron"));
            });

            assertTrue(resultHolder[0].commandResponse().isPresent());
            assertTrue(resultHolder[0].commandResponse().get().contains("Usage"));
        }
    }

    @Test
    void executeReturnsInvalidForBadUuid() {
        try (MockedStatic<Arc> arcStatic = mockStatic(Arc.class)) {
            ArcContainer mockContainer = mock(ArcContainer.class);
            ManagedContext mockContext = mock(ManagedContext.class);
            arcStatic.when(Arc::container).thenReturn(mockContainer);
            when(mockContainer.requestContext()).thenReturn(mockContext);
            when(mockContext.isActive()).thenReturn(false);

            ReflexResult[] resultHolder = new ReflexResult[1];
            WorkspaceScopeHolder.run("sess-1", "user1", "chat1", () -> {
                String input = "deletecron not-a-uuid";
                resultHolder[0] = reflex.execute("sess-1", "user1", "chat1", input, context(input));
            });

            assertTrue(resultHolder[0].commandResponse().isPresent());
            assertTrue(resultHolder[0].commandResponse().get().contains("Invalid cron ID"));
        }
    }

    @Test
    void executeReturnsNotFoundWhenDeleteReturnsZero() {
        try (MockedStatic<Arc> arcStatic = mockStatic(Arc.class)) {
            ArcContainer mockContainer = mock(ArcContainer.class);
            ManagedContext mockContext = mock(ManagedContext.class);
            arcStatic.when(Arc::container).thenReturn(mockContainer);
            when(mockContainer.requestContext()).thenReturn(mockContext);
            when(mockContext.isActive()).thenReturn(false);

            when(cronStore.deleteByIdAndUserId(VALID_UUID, "user1")).thenReturn(0);

            ReflexResult[] resultHolder = new ReflexResult[1];
            WorkspaceScopeHolder.run("sess-1", "user1", "chat1", () -> {
                String input = "deletecron " + VALID_UUID;
                resultHolder[0] = reflex.execute("sess-1", "user1", "chat1", input, context(input));
            });

            assertTrue(resultHolder[0].commandResponse().isPresent());
            assertTrue(resultHolder[0].commandResponse().get().contains("not found"));
        }
    }

    @Test
    void executeDeletesCronAndRecordsAudit() {
        try (MockedStatic<Arc> arcStatic = mockStatic(Arc.class)) {
            ArcContainer mockContainer = mock(ArcContainer.class);
            ManagedContext mockContext = mock(ManagedContext.class);
            arcStatic.when(Arc::container).thenReturn(mockContainer);
            when(mockContainer.requestContext()).thenReturn(mockContext);
            when(mockContext.isActive()).thenReturn(false);

            when(cronStore.deleteByIdAndUserId(VALID_UUID, "user1")).thenReturn(1);

            ReflexResult[] resultHolder = new ReflexResult[1];
            WorkspaceScopeHolder.run("sess-1", "user1", "chat1", () -> {
                String input = "deletecron " + VALID_UUID;
                resultHolder[0] = reflex.execute("sess-1", "user1", "chat1", input, context(input));
            });

            assertTrue(resultHolder[0].commandResponse().isPresent());
            assertTrue(resultHolder[0].commandResponse().get().contains("deleted"));
            verify(auditService)
                    .record(
                            eq("sess-1"),
                            eq("user1"),
                            anyString(),
                            eq(SecurityEventType.CRONDELETED),
                            contains(VALID_UUID));
        }
    }

    @Test
    void executeHandlesSlashCronDeleteForm() {
        try (MockedStatic<Arc> arcStatic = mockStatic(Arc.class)) {
            ArcContainer mockContainer = mock(ArcContainer.class);
            ManagedContext mockContext = mock(ManagedContext.class);
            arcStatic.when(Arc::container).thenReturn(mockContainer);
            when(mockContainer.requestContext()).thenReturn(mockContext);
            when(mockContext.isActive()).thenReturn(false);

            when(cronStore.deleteByIdAndUserId(VALID_UUID, "user1")).thenReturn(1);

            ReflexResult[] resultHolder = new ReflexResult[1];
            WorkspaceScopeHolder.run("sess-1", "user1", "chat1", () -> {
                String input = "/cron delete " + VALID_UUID;
                resultHolder[0] = reflex.execute("sess-1", "user1", "chat1", input, context(input));
            });

            assertTrue(resultHolder[0].commandResponse().get().contains("deleted"));
            verify(cronStore).deleteByIdAndUserId(VALID_UUID, "user1");
        }
    }
}
