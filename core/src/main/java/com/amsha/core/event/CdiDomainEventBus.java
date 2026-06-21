package com.amsha.core.event;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.jboss.logging.Logger;

@ApplicationScoped
public class CdiDomainEventBus implements DomainEventBus {

    private static final Logger log = Logger.getLogger(CdiDomainEventBus.class);

    @Inject
    Event<Object> event;

    @Override
    public <T> void publish(Class<T> eventType, T eventPayload) {
        Objects.requireNonNull(eventPayload, "eventPayload must not be null");
        if (!eventType.isInstance(eventPayload)) {
            log.errorf(
                    "Event type mismatch: catalog declares %s but payload is %s",
                    eventType.getName(), eventPayload.getClass().getName());
            throw new IllegalArgumentException("Event type mismatch: " + eventType.getName() + " != "
                    + eventPayload.getClass().getName());
        }
        event.fire(eventPayload);
    }

    @Override
    public <T> CompletableFuture<Void> publishAsync(Class<T> eventType, T eventPayload) {
        Objects.requireNonNull(eventPayload, "eventPayload must not be null");
        if (!eventType.isInstance(eventPayload)) {
            log.errorf(
                    "Event type mismatch: catalog declares %s but payload is %s",
                    eventType.getName(), eventPayload.getClass().getName());
            throw new IllegalArgumentException("Event type mismatch: " + eventType.getName() + " != "
                    + eventPayload.getClass().getName());
        }
        return event.fireAsync(eventPayload).thenApply(_ -> (Void) null).toCompletableFuture();
    }
}
