package com.amsha.core.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * Restart-free runtime control for the LangChain4j {@code ChatModelListener} migration pilot.
 *
 * <p>A configured or requested enable ({@code SHADOW} or {@code ACTIVE}) becomes effective
 * only while a verified ready active implementation is registered.
 */
@ApplicationScoped
@Unremovable
public class ChatModelListenerRuntimeControl {

    private final AtomicReference<State> state;

    @Inject
    public ChatModelListenerRuntimeControl(
            @ConfigProperty(name = "amsha.observability.chat-listener.mode", defaultValue = "off")
                    String configuredMode) {
        ChatModelListenerMode mode = ChatModelListenerMode.fromString(configuredMode);
        this.state = new AtomicReference<>(new State(mode, ChatModelListenerMode.OFF, false, false, 0, 0));
    }

    public Snapshot snapshot() {
        State current = state.get();
        return new Snapshot(
                current.requestedMode(),
                current.effectiveMode(),
                current.implementationAvailable(),
                current.acceptingRequests(),
                current.draining(),
                current.inFlight(),
                current.revision());
    }

    public ChatModelListenerMode requestedMode() {
        return state.get().requestedMode();
    }

    public ChatModelListenerMode effectiveMode() {
        return state.get().effectiveMode();
    }

    /**
     * Changes the requested listener mode. This is intentionally package-private: a future
     * runtime/admin adapter must perform internal authorization and mandatory audit-before-
     * mutation before calling this control-plane operation.
     */
    ToggleResult setMode(ChatModelListenerMode targetMode) {
        Objects.requireNonNull(targetMode, "targetMode");
        while (true) {
            State current = state.get();
            if (targetMode != ChatModelListenerMode.OFF && !current.implementationAvailable()) {
                State updated = current.withRequestedMode(targetMode);
                if (state.compareAndSet(current, updated)) {
                    return ToggleResult.REJECTED_MISSING_IMPLEMENTATION;
                }
                continue;
            }

            ChatModelListenerMode nextEffective = targetMode;
            boolean draining = targetMode == ChatModelListenerMode.OFF && current.inFlight() > 0;
            State next = current.transition(targetMode, nextEffective, draining);
            if (state.compareAndSet(current, next)) {
                return draining ? ToggleResult.DRAINING : ToggleResult.ACCEPTED;
            }
        }
    }

    /**
     * Registers one verified and ready active listener implementation.
     */
    public Registration registerReadyImplementation() {
        while (true) {
            State current = state.get();
            ChatModelListenerMode nextEffective = current.requestedMode();
            State next = current.withImplementation(true, nextEffective);
            if (state.compareAndSet(current, next)) {
                return new Registration(this);
            }
        }
    }

    private void unregisterImplementation() {
        while (true) {
            State current = state.get();
            State next = current.withImplementation(false, ChatModelListenerMode.OFF);
            if (state.compareAndSet(current, next)) {
                return;
            }
        }
    }

    /**
     * Acquires one active listener invocation lease only while effective mode is active or shadow.
     */
    public Optional<Lease> tryAcquire() {
        while (true) {
            State current = state.get();
            if (!current.acceptingRequests()) {
                return Optional.empty();
            }
            State updated = current.withInFlight(current.inFlight() + 1);
            if (state.compareAndSet(current, updated)) {
                return Optional.of(new Lease(this, current.effectiveMode()));
            }
        }
    }

    private void release() {
        while (true) {
            State current = state.get();
            if (current.inFlight() <= 0) {
                throw new IllegalStateException("ChatModelListener lease count underflow");
            }
            int newInFlight = current.inFlight() - 1;
            boolean newDraining = current.draining() && newInFlight > 0;
            State updated = current.withInFlightAndDraining(newInFlight, newDraining);
            if (state.compareAndSet(current, updated)) {
                return;
            }
        }
    }

    public enum ToggleResult {
        ACCEPTED,
        REJECTED_MISSING_IMPLEMENTATION,
        DRAINING
    }

    public record Snapshot(
            ChatModelListenerMode requestedMode,
            ChatModelListenerMode effectiveMode,
            boolean implementationAvailable,
            boolean acceptingRequests,
            boolean draining,
            int inFlight,
            long revision) {}

    public static final class Lease implements AutoCloseable {
        private final ChatModelListenerRuntimeControl control;
        private final ChatModelListenerMode mode;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Lease(ChatModelListenerRuntimeControl control, ChatModelListenerMode mode) {
            this.control = control;
            this.mode = mode;
        }

        public ChatModelListenerMode mode() {
            return mode;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                control.release();
            }
        }
    }

    public static final class Registration implements AutoCloseable {
        private final ChatModelListenerRuntimeControl control;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Registration(ChatModelListenerRuntimeControl control) {
            this.control = control;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                control.unregisterImplementation();
            }
        }
    }

    private record State(
            ChatModelListenerMode requestedMode,
            ChatModelListenerMode effectiveMode,
            boolean implementationAvailable,
            boolean draining,
            int inFlight,
            long revision) {

        boolean acceptingRequests() {
            return implementationAvailable && effectiveMode != ChatModelListenerMode.OFF;
        }

        State withRequestedMode(ChatModelListenerMode newRequested) {
            return new State(newRequested, effectiveMode, implementationAvailable, draining, inFlight, revision + 1);
        }

        State withImplementation(boolean available, ChatModelListenerMode newEffective) {
            boolean newDraining = !available && inFlight > 0;
            return new State(requestedMode, newEffective, available, newDraining, inFlight, revision + 1);
        }

        State transition(ChatModelListenerMode newRequested, ChatModelListenerMode newEffective, boolean newDraining) {
            return new State(newRequested, newEffective, implementationAvailable, newDraining, inFlight, revision + 1);
        }

        State withInFlight(int newInFlight) {
            return new State(requestedMode, effectiveMode, implementationAvailable, draining, newInFlight, revision);
        }

        State withInFlightAndDraining(int newInFlight, boolean newDraining) {
            return new State(requestedMode, effectiveMode, implementationAvailable, newDraining, newInFlight, revision);
        }
    }
}
