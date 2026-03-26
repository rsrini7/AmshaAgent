package com.arcclaw.core.pipeline.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.event.Event;

import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.compaction.CompactionRequest;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CompactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompactionHandlerTest {

    @Mock private CompactionService compactionService;
    @Mock private Event<CompactionRequest> compactionEvent;
    @Mock private CompactionInFlightFlag compactionInFlight;

    @InjectMocks
    private CompactionHandler handler;

    private MessageContext mockContext(String sessionId, String userId) {
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.getSessionId()).thenReturn(Optional.of(sessionId));
        ChannelUser user = mock(ChannelUser.class);
        when(user.platformId()).thenReturn(userId);
        when(ctx.getUser()).thenReturn(user);
        return ctx;
    }

    @Test
    void testHandleReturnsProceedImmediately() {
        WorkspaceContext.run("session1", "user1", "chat1", AutonomyLevel.FULL, "coordinator", () -> {
            MessageContext ctx = mockContext("session1", "user1");
            when(compactionService.shouldCompact("session1")).thenReturn(true);
            when(compactionInFlight.compareAndSet("session1", false, true)).thenReturn(true);

            CompletableFuture<CompactionRequest> future = new CompletableFuture<>();
            when(compactionEvent.fireAsync(any(CompactionRequest.class))).thenReturn(future);

            HandleResult result = handler.handle(ctx);

            assertEquals(HandleResult.CONTINUE, result);
        });
    }

    @Test
    void testFiresAsyncEventWhenNeeded() {
        WorkspaceContext.run("session1", "user1", "chat1", AutonomyLevel.FULL, "coordinator", () -> {
            MessageContext ctx = mockContext("session1", "user1");
            when(compactionService.shouldCompact("session1")).thenReturn(true);
            when(compactionInFlight.compareAndSet("session1", false, true)).thenReturn(true);
            when(compactionEvent.fireAsync(any(CompactionRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            handler.handle(ctx);

            ArgumentCaptor<CompactionRequest> captor =
                    ArgumentCaptor.forClass(CompactionRequest.class);
            verify(compactionEvent, times(1)).fireAsync(captor.capture());
            assertEquals("session1", captor.getValue().sessionId());
            assertEquals("user1", captor.getValue().userId());
        });
    }

    @Test
    void testDoesNotFireIfAlreadyInFlight() {
        WorkspaceContext.run("session1", "user1", "chat1", AutonomyLevel.FULL, "coordinator", () -> {
            MessageContext ctx = mockContext("session1", "user1");
            when(compactionService.shouldCompact("session1")).thenReturn(true);
            when(compactionInFlight.compareAndSet("session1", false, true)).thenReturn(false);

            handler.handle(ctx);

            verify(compactionEvent, never()).fireAsync(any());
        });
    }

    @Test
    void testDoesNotFireIfCompactionNotNeeded() {
        WorkspaceContext.run("session1", "user1", "chat1", AutonomyLevel.FULL, "coordinator", () -> {
            MessageContext ctx = mockContext("session1", "user1");
            when(compactionService.shouldCompact("session1")).thenReturn(false);

            handler.handle(ctx);

            verify(compactionEvent, never()).fireAsync(any());
            verify(compactionInFlight, never()).compareAndSet(anyString(), anyBoolean(), anyBoolean());
        });
    }

    @Test
    void testHandleExecutionTimeIsLow() {
        WorkspaceContext.run("session1", "user1", "chat1", AutonomyLevel.FULL, "coordinator", () -> {
            MessageContext ctx = mockContext("session1", "user1");
            when(compactionService.shouldCompact("session1")).thenReturn(true);
            when(compactionInFlight.compareAndSet("session1", false, true)).thenReturn(true);
            when(compactionEvent.fireAsync(any())).thenAnswer(invocation -> {
                Thread.sleep(10);
                return CompletableFuture.completedFuture(null);
            });

            assertTimeout(Duration.ofMillis(100), () -> handler.handle(ctx));
        });
    }

    @Test
    void testSecondCompactionTriggeredAfterFirstButNotReleasedByHandler() {
        WorkspaceContext.run("session1", "user1", "chat1", AutonomyLevel.FULL, "coordinator", () -> {
            MessageContext ctx = mockContext("session1", "user1");
            when(compactionService.shouldCompact("session1")).thenReturn(true);
            
            // First call
            when(compactionInFlight.compareAndSet("session1", false, true)).thenReturn(true);
            when(compactionEvent.fireAsync(any())).thenReturn(CompletableFuture.completedFuture(null));

            handler.handle(ctx);
            
            // Second call - should skip because handler didn't release it
            when(compactionInFlight.compareAndSet("session1", false, true)).thenReturn(false);

            handler.handle(ctx);

            verify(compactionEvent, times(1)).fireAsync(any());
        });
    }
}
