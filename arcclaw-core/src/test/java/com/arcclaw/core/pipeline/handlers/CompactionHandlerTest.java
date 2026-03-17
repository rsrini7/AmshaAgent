package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.compaction.CompactionRequest;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.ContextCompactionService;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompactionHandlerTest {

    private CompactionHandler handler;
    private ContextCompactionService compactionService;
    private Event<CompactionRequest> compactionEvent;
    private CompactionInFlightFlag compactionInFlight;

    @BeforeEach
    void setUp() {
        handler = new CompactionHandler();
        compactionService = mock(ContextCompactionService.class);
        @SuppressWarnings("unchecked")
        Event<CompactionRequest> compactionEvent = mock(Event.class);
        compactionInFlight = mock(CompactionInFlightFlag.class);

        handler.compactionService = compactionService;
        handler.compactionEvent = compactionEvent;
        handler.compactionInFlight = compactionInFlight;
    }

    @Test
    void testHandleReturnsProceedImmediately() {
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.getSessionId()).thenReturn(Optional.of("session1"));
        ChannelUser user = mock(ChannelUser.class);
        when(user.platformId()).thenReturn("user1");
        when(ctx.getUser()).thenReturn(user);

        when(compactionService.shouldCompact("session1")).thenReturn(true);
        when(compactionInFlight.compareAndSet(false, true)).thenReturn(true);

        // Mock fireAsync to return a future that hasn't completed yet (simulating async
        // work)
        CompletableFuture<CompactionRequest> future = new CompletableFuture<>();
        when(compactionEvent.fireAsync(any(CompactionRequest.class))).thenReturn(future);

        HandleResult result = handler.handle(ctx);

        assertEquals(HandleResult.CONTINUE, result);
        assertFalse(future.isDone(), "Should not await fireAsync result");
    }

    @Test
    void testFiresAsyncEventWhenNeeded() {
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.getSessionId()).thenReturn(Optional.of("session1"));
        ChannelUser user = mock(ChannelUser.class);
        when(user.platformId()).thenReturn("user1");
        when(ctx.getUser()).thenReturn(user);

        when(compactionService.shouldCompact("session1")).thenReturn(true);
        when(compactionInFlight.compareAndSet(false, true)).thenReturn(true);
        when(compactionEvent.fireAsync(any(CompactionRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        handler.handle(ctx);

        ArgumentCaptor<CompactionRequest> captor = ArgumentCaptor.forClass(CompactionRequest.class);
        verify(compactionEvent, times(1)).fireAsync(captor.capture());
        assertEquals("session1", captor.getValue().sessionId());
        assertEquals("user1", captor.getValue().userId());
    }

    @Test
    void testDoesNotFireIfAlreadyInFlight() {
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.getSessionId()).thenReturn(Optional.of("session1"));
        ChannelUser user = mock(ChannelUser.class);
        when(user.platformId()).thenReturn("user1");
        when(ctx.getUser()).thenReturn(user);

        when(compactionService.shouldCompact("session1")).thenReturn(true);
        when(compactionInFlight.compareAndSet(false, true)).thenReturn(false);

        handler.handle(ctx);

        verify(compactionEvent, never()).fireAsync(any());
    }

    @Test
    void testHandleExecutionTimeIsLow() {
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.getSessionId()).thenReturn(Optional.of("session1"));
        ChannelUser user = mock(ChannelUser.class);
        when(user.platformId()).thenReturn("user1");
        when(ctx.getUser()).thenReturn(user);

        when(compactionService.shouldCompact("session1")).thenReturn(true);
        when(compactionInFlight.compareAndSet(false, true)).thenReturn(true);

        // Simulate a slow fireAsync delivery (though fireAsync itself should be fast)
        when(compactionEvent.fireAsync(any())).thenAnswer(invocation -> {
            Thread.sleep(50); // This sleep happens DURING fireAsync call in this mock
            return CompletableFuture.completedFuture(null);
        });

        // We want to ensure that even if fireAsync is slightly slow or something,
        // the overall handle call doesn't block on the ACTUAL COMPACTION.
        // But here we're mocking fireAsync.

        assertTimeout(Duration.ofMillis(100), () -> {
            handler.handle(ctx);
        });
    }
}
