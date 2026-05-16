package com.arcclaw.core.event;

import java.util.concurrent.CompletableFuture;

public interface DomainEventBus {

    <T> void publish(Class<T> eventType, T event);

    <T> CompletableFuture<Void> publishAsync(Class<T> eventType, T event);
}
