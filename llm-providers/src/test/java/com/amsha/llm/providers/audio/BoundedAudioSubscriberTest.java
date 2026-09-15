package com.amsha.llm.providers.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import com.amsha.llm.providers.audio.OmlxSpeechSynthesisProvider.BoundedAudioSubscriber;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class BoundedAudioSubscriberTest {

    @Test
    void assemblesChunksUnderLimit() throws Exception {
        BoundedAudioSubscriber sub = new BoundedAudioSubscriber(100);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        sub.onSubscribe(subscription);
        sub.onNext(List.of(ByteBuffer.wrap(new byte[50])));
        sub.onNext(List.of(ByteBuffer.wrap(new byte[40])));
        sub.onComplete();

        assertArrayEquals(new byte[90], sub.getBody().toCompletableFuture().get());
        verify(subscription, Mockito.never()).cancel();
    }

    @Test
    void abortsUpstreamWhenSingleChunkExceedsLimit() throws Exception {
        BoundedAudioSubscriber sub = new BoundedAudioSubscriber(100);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        sub.onSubscribe(subscription);
        sub.onNext(List.of(ByteBuffer.wrap(new byte[150])));

        CompletableFuture<byte[]> future = sub.getBody().toCompletableFuture();
        assertTrue(future.isCompletedExceptionally());
        assertThrows(Exception.class, future::get);
        verify(subscription).cancel();
    }

    @Test
    void abortsUpstreamMidStreamBeforeBufferGrowsPastLimit() throws Exception {
        BoundedAudioSubscriber sub = new BoundedAudioSubscriber(100);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        sub.onSubscribe(subscription);
        sub.onNext(List.of(ByteBuffer.wrap(new byte[60])));
        sub.onNext(List.of(ByteBuffer.wrap(new byte[60]))); // 60 + 60 > 100 -> abort before append

        CompletableFuture<byte[]> future = sub.getBody().toCompletableFuture();
        assertTrue(future.isCompletedExceptionally());
        assertThrows(Exception.class, future::get);
        verify(subscription).cancel();
    }

    @Test
    void chunksArrivingAfterCompletionAreIgnored() throws Exception {
        BoundedAudioSubscriber sub = new BoundedAudioSubscriber(100);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        sub.onSubscribe(subscription);
        sub.onNext(List.of(ByteBuffer.wrap(new byte[10])));
        sub.onComplete();
        sub.onNext(List.of(ByteBuffer.wrap(new byte[10]))); // late chunk must not be appended

        assertArrayEquals(new byte[10], sub.getBody().toCompletableFuture().get());
    }

    @Test
    void upstreamErrorFailsTheBodyFuture() {
        BoundedAudioSubscriber sub = new BoundedAudioSubscriber(100);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        sub.onSubscribe(subscription);
        sub.onError(new IllegalStateException("upstream reset"));

        CompletableFuture<byte[]> future = sub.getBody().toCompletableFuture();
        assertTrue(future.isCompletedExceptionally());
        assertThrows(Exception.class, future::get);
    }
}
