package com.amsha.localml.onnx;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * Restart-free Phase 3 classifier kill switches with in-flight lease tracking.
 *
 * <p>A configured or requested enable becomes effective only while a ready active
 * implementation is registered. Phase 2 shadow classifiers do not register here.
 */
@ApplicationScoped
@Unremovable
public class OnnxClassifierRuntimeControl {

    private final Map<Classifier, Slot> slots;

    @Inject
    public OnnxClassifierRuntimeControl(
            @ConfigProperty(name = "amsha.onnx.classifier.intent.enabled", defaultValue = "false")
                    boolean intentEnabled,
            @ConfigProperty(name = "amsha.onnx.classifier.tool-risk.enabled", defaultValue = "false")
                    boolean toolRiskEnabled,
            @ConfigProperty(name = "amsha.onnx.classifier.answer-quality.enabled", defaultValue = "false")
                    boolean answerQualityEnabled) {
        this.slots = createSlots(intentEnabled, toolRiskEnabled, answerQualityEnabled);
    }

    public List<Snapshot> snapshots() {
        return List.of(Classifier.values()).stream().map(this::snapshot).toList();
    }

    public Snapshot snapshot(Classifier classifier) {
        return slot(classifier).snapshot(classifier);
    }

    /** Changes a runtime request; enable is rejected until a ready active implementation exists. */
    public ToggleResult setEnabled(Classifier classifier, boolean enabled) {
        return slot(classifier).setEnabled(classifier, enabled);
    }

    /**
     * Registers one verified and promotion-approved active implementation.
     *
     * <p>Shadow-only classifiers MUST NOT call this method.
     */
    public Registration registerReadyImplementation(Classifier classifier) {
        Slot slot = slot(classifier);
        slot.registerImplementation();
        return new Registration(slot);
    }

    /** Acquires one active inference lease only while the classifier is effectively enabled. */
    public Optional<Lease> tryAcquire(Classifier classifier) {
        Slot slot = slot(classifier);
        return slot.tryAcquire().map(ignored -> new Lease(slot));
    }

    private Slot slot(Classifier classifier) {
        return slots.get(Objects.requireNonNull(classifier, "classifier"));
    }

    private static Map<Classifier, Slot> createSlots(
            boolean intentEnabled, boolean toolRiskEnabled, boolean answerQualityEnabled) {
        EnumMap<Classifier, Slot> configured = new EnumMap<>(Classifier.class);
        configured.put(Classifier.INTENT, new Slot(intentEnabled));
        configured.put(Classifier.TOOL_RISK, new Slot(toolRiskEnabled));
        configured.put(Classifier.ANSWER_QUALITY, new Slot(answerQualityEnabled));
        return Map.copyOf(configured);
    }

    public enum Classifier {
        INTENT("intent"),
        TOOL_RISK("tool-risk"),
        ANSWER_QUALITY("answer-quality");

        private final String id;

        Classifier(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Optional<Classifier> fromId(String id) {
            if (id == null) return Optional.empty();
            for (Classifier classifier : values()) {
                if (classifier.id.equals(id)) return Optional.of(classifier);
            }
            return Optional.empty();
        }
    }

    public record Snapshot(
            String classifier,
            boolean startupEnabled,
            boolean requestedEnabled,
            boolean implementationAvailable,
            boolean effectiveEnabled,
            boolean acceptingRequests,
            boolean draining,
            int inFlight,
            long revision) {}

    public record ToggleResult(boolean accepted, boolean changed, String reason, Snapshot snapshot) {
        public ToggleResult {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public static final class Registration implements AutoCloseable {
        private final Slot slot;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(Slot slot) {
            this.slot = slot;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) slot.unregisterImplementation();
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Slot slot;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Slot slot) {
            this.slot = slot;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) slot.release();
        }
    }

    private static final class Slot {
        private final boolean startupEnabled;
        private final AtomicReference<State> state;

        private Slot(boolean startupEnabled) {
            this.startupEnabled = startupEnabled;
            this.state = new AtomicReference<>(new State(startupEnabled, 0, 0, 0));
        }

        private Snapshot snapshot(Classifier classifier) {
            State current = state.get();
            boolean accepting = current.requestedEnabled() && current.readyImplementations() > 0;
            return new Snapshot(
                    classifier.id(),
                    startupEnabled,
                    current.requestedEnabled(),
                    current.readyImplementations() > 0,
                    accepting,
                    accepting,
                    !accepting && current.inFlight() > 0,
                    current.inFlight(),
                    current.revision());
        }

        private ToggleResult setEnabled(Classifier classifier, boolean enabled) {
            while (true) {
                State current = state.get();
                if (enabled && current.readyImplementations() == 0) {
                    return new ToggleResult(false, false, "MISSING_READY_IMPLEMENTATION", snapshot(classifier));
                }
                if (current.requestedEnabled() == enabled) {
                    return new ToggleResult(true, false, "UNCHANGED", snapshot(classifier));
                }
                State updated =
                        new State(enabled, current.readyImplementations(), current.inFlight(), current.revision() + 1);
                if (state.compareAndSet(current, updated)) {
                    return new ToggleResult(true, true, "UPDATED", snapshot(classifier));
                }
            }
        }

        private void registerImplementation() {
            updateRegistrations(1);
        }

        private void unregisterImplementation() {
            updateRegistrations(-1);
        }

        private void updateRegistrations(int delta) {
            while (true) {
                State current = state.get();
                int updatedCount = current.readyImplementations() + delta;
                if (updatedCount < 0) throw new IllegalStateException("Classifier implementation count underflow");
                State updated =
                        new State(current.requestedEnabled(), updatedCount, current.inFlight(), current.revision() + 1);
                if (state.compareAndSet(current, updated)) return;
            }
        }

        private Optional<State> tryAcquire() {
            while (true) {
                State current = state.get();
                if (!current.requestedEnabled() || current.readyImplementations() == 0) {
                    return Optional.empty();
                }
                State updated =
                        new State(true, current.readyImplementations(), current.inFlight() + 1, current.revision());
                if (state.compareAndSet(current, updated)) return Optional.of(updated);
            }
        }

        private void release() {
            while (true) {
                State current = state.get();
                if (current.inFlight() <= 0) throw new IllegalStateException("Classifier lease count underflow");
                State updated = new State(
                        current.requestedEnabled(),
                        current.readyImplementations(),
                        current.inFlight() - 1,
                        current.revision());
                if (state.compareAndSet(current, updated)) return;
            }
        }
    }

    private record State(boolean requestedEnabled, int readyImplementations, int inFlight, long revision) {}
}
