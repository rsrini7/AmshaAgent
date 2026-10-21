package com.amsha.llm.providers.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTTP body subscriber that cancels the response before the configured bound is exceeded. */
final class BoundedResponseSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final long byteLimit;
    private final AtomicBoolean exceeded;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private long receivedBytes;

    private BoundedResponseSubscriber(long byteLimit, AtomicBoolean exceeded) {
        this.byteLimit = byteLimit;
        this.exceeded = exceeded;
    }

    static BoundedResponseHandler handler(long byteLimit) {
        return new BoundedResponseHandler(byteLimit);
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        if (body.isDone()) {
            value.cancel();
        } else {
            value.request(Long.MAX_VALUE);
        }
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        if (body.isDone()) {
            return;
        }
        for (ByteBuffer item : items) {
            int itemBytes = item.remaining();
            if (itemBytes > byteLimit - receivedBytes) {
                cancelOversizedResponse();
                return;
            }
            byte[] chunk = new byte[itemBytes];
            item.get(chunk);
            buffer.writeBytes(chunk);
            receivedBytes += itemBytes;
        }
    }

    @Override
    public void onError(Throwable error) {
        body.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
        body.complete(buffer.toByteArray());
    }

    private void cancelOversizedResponse() {
        exceeded.set(true);
        if (subscription != null) {
            subscription.cancel();
        }
        body.completeExceptionally(new ResponseTooLargeException("provider response exceeded configured limit"));
    }

    static final class BoundedResponseHandler implements HttpResponse.BodyHandler<byte[]> {
        private final long byteLimit;
        private final AtomicBoolean exceeded = new AtomicBoolean();

        private BoundedResponseHandler(long byteLimit) {
            if (byteLimit < 1) {
                throw new IllegalArgumentException("byteLimit must be positive");
            }
            this.byteLimit = byteLimit;
        }

        @Override
        public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo responseInfo) {
            return new BoundedResponseSubscriber(byteLimit, exceeded);
        }

        boolean exceeded() {
            return exceeded.get();
        }
    }

    /** Signals that a provider response exceeded its bounded body limit. */
    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(String message) {
            super(message);
        }
    }
}
