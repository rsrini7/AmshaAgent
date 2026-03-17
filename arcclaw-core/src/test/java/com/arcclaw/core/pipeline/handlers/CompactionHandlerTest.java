package com.arcclaw.core.pipeline.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.compaction.CompactionRequest;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.ContextCompactionService;

import jakarta.enterprise.event.Event;

@ExtendWith(MockitoExtension.class)
class CompactionHandlerTest {

    @Mock
    private ContextCompactionService compactionService;

    @Mock
    private Event<CompactionRequest> compactionEvent;

    @Mock
    private CompactionInFlightFlag compactionInFlight;

    @InjectMocks
    private CompactionHandler handler;

    @Test
    void testHandleReturnsProceedImmediately() {
        MessageContext ctx = mock(MessageContext.class);
        when(ctx.getSessionId()).thenReturn(Optional.of("session1"));
        ChannelUser user = mock(ChannelUser.class);
        when(user.platformId()).thenReturn("user1");
        when(ctx.getUser()).thenReturn(user);

        when(compactionService.shouldCompact("session1")).thenReturn(true);
        when(compactionInFlight.compareAndSet(false, true)).thenReturn(true);

        // fireAsync returns CompletionStage, CompletableFuture is a valid
        // implementation
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

        // Simulate a slightly slow fireAsync delivery call itself (the method call, not
        // the background work)
        when(compactionEvent.fireAsync(any())).thenAnswer(invocation -> {
            Thread.sleep(10);
            return CompletableFuture.completedFuture(null);
        });

        assertTimeout(Duration.ofMillis(100), () -> {
            handler.handle(ctx);
        });
    }
}
