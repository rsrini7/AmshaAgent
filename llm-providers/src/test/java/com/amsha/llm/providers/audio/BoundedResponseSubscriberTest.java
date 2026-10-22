package com.amsha.llm.providers.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.Test;

class BoundedResponseSubscriberTest {
    @Test
    void completesWithinLimitWithoutMarkingOverflow() throws Exception {
        BoundedResponseSubscriber.BoundedResponseHandler handler = BoundedResponseSubscriber.handler(100);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        var subscriber = handler.apply(null);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[50]), ByteBuffer.wrap(new byte[40])));
        subscriber.onComplete();

        assertFalse(handler.exceeded());
        assertArrayEquals(
                new byte[90], subscriber.getBody().toCompletableFuture().get());
    }

    @Test
    void cancelsAndMarksOverflowBeforeAppendingAnOversizedChunk() {
        BoundedResponseSubscriber.BoundedResponseHandler handler = BoundedResponseSubscriber.handler(100);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        var subscriber = handler.apply(null);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[101])));

        assertTrue(handler.exceeded());
        assertThrows(
                Exception.class,
                () -> subscriber.getBody().toCompletableFuture().get());
        verify(subscription).cancel();
    }

    @Test
    void detectsOverflowAcrossMultipleChunks() {
        BoundedResponseSubscriber.BoundedResponseHandler handler = BoundedResponseSubscriber.handler(100);
        var subscriber = handler.apply(null);
        subscriber.onSubscribe(mock(Flow.Subscription.class));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[60])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[60])));

        assertTrue(handler.exceeded());
        assertThrows(
                Exception.class,
                () -> subscriber.getBody().toCompletableFuture().get());
    }
}
