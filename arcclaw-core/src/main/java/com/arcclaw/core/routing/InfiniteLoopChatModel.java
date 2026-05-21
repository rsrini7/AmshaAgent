package com.arcclaw.core.routing;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.arcclaw.core.agent.reasoning.error.LlmAuthenticationException;
import com.arcclaw.core.agent.reasoning.error.LlmContentFilterException;
import com.arcclaw.core.agent.reasoning.error.LlmExceptionClassifier;
import com.arcclaw.core.agent.reasoning.error.LlmProviderException;
import com.arcclaw.core.agent.reasoning.error.LlmRateLimitException;
import com.arcclaw.core.agent.reasoning.error.LlmServiceUnavailableException;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.scope.WorkspaceContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

public class InfiniteLoopChatModel implements ChatModel {

    private static final Logger log = Logger.getLogger(InfiniteLoopChatModel.class);
    private static final long MAX_COOLDOWN_WAIT_MS = 300_000L;
    private static final int QUALITY_FAILURE_THRESHOLD = 3;

    public record Slot(String name, ChatModel model) {}

    // Static registry for quality failure reporting from outside (e.g., FinishOutcomeProcessor)
    // Uses WeakReference so old instances can be GC'd when replaced (H8).
    private static final ConcurrentHashMap<String, WeakReference<InfiniteLoopChatModel>> instances = new ConcurrentHashMap<>();
    private static final ScopedValue<String> LAST_USED_SLOT = ScopedValue.newInstance();

    private final List<Slot> slots;
    private final Map<String, SlotHealth> health;
    private final AtomicInteger currentIndex;
    private final int cooldownBaseSeconds;
    private final int cooldownMaxSeconds;
    private final int cooldownTransientSeconds;
    private final long maxLatencyMs;

    public InfiniteLoopChatModel(List<Slot> slots, int cooldownBaseSeconds, int cooldownMaxSeconds, int cooldownTransientSeconds, long maxLatencyMs) {
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException("InfiniteLoopChatModel requires at least one slot");
        }
        this.slots = List.copyOf(slots);
        this.health = new ConcurrentHashMap<>();
        for (Slot s : slots) {
            health.put(s.name(), new SlotHealth());
            instances.put(s.name(), new WeakReference<>(this));
        }
        this.currentIndex = new AtomicInteger(0);
        this.cooldownBaseSeconds = cooldownBaseSeconds;
        this.cooldownMaxSeconds = cooldownMaxSeconds;
        this.cooldownTransientSeconds = cooldownTransientSeconds;
        this.maxLatencyMs = maxLatencyMs;
    }

    /** Returns the slot name that served the most recent chat() call in this scope. */
    public static Optional<String> getLastUsedSlot() {
        if (LAST_USED_SLOT.isBound() && LAST_USED_SLOT.get() != null) {
            return Optional.of(LAST_USED_SLOT.get());
        }
        return Optional.empty();
    }

    /** Records a quality failure for the given slot. After threshold failures, the slot enters cooldown. */
    public static void recordQualityFailure(String slotName) {
        WeakReference<InfiniteLoopChatModel> ref = instances.get(slotName);
        if (ref == null) {
            log.warnf("InfiniteLoop: recordQualityFailure called for unknown slot [%s] — check slot registration or ScopedValue propagation", slotName);
            return;
        }
        InfiniteLoopChatModel instance = ref.get();
        if (instance == null) {
            instances.remove(slotName, ref);
            log.warnf("InfiniteLoop: recordQualityFailure stale reference for slot [%s] — removed", slotName);
            return;
        }
        SlotHealth h = instance.health.get(slotName);
        if (h == null) return;
        h.recordQualityFailure(instance.cooldownTransientSeconds);
        if (h.qualityFailures >= QUALITY_FAILURE_THRESHOLD) {
            log.warnf("InfiniteLoop: Slot [%s] hit %d quality failures — cooling down %ds.",
                    slotName, h.qualityFailures, instance.cooldownTransientSeconds);
        } else {
            log.infof("InfiniteLoop: Slot [%s] quality failure %d/%d.",
                    slotName, h.qualityFailures, QUALITY_FAILURE_THRESHOLD);
        }
    }

    /** Returns a summary of slot health for debugging. */
    public static String getSlotStats() {
        StringBuilder sb = new StringBuilder();
        var it = instances.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            InfiniteLoopChatModel instance = entry.getValue().get();
            if (instance == null) {
                it.remove();
                continue;
            }
            SlotHealth h = instance.health.get(entry.getKey());
            if (h != null) {
                sb.append(entry.getKey()).append(":qf=").append(h.qualityFailures)
                  .append(":cf=").append(h.consecutiveFailures)
                  .append(":disabled=").append(h.permanentlyDisabled)
                  .append(" ");
            }
        }
        return sb.toString().trim();
    }

    public InfiniteLoopChatModel(List<Slot> slots, int cooldownBaseSeconds, int cooldownMaxSeconds, int cooldownTransientSeconds) {
        this(slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, 60_000L);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int n = slots.size();

        while (true) {
            int start = currentIndex.updateAndGet(i -> (i + 1) % n);

            for (int offset = 0; offset < n; offset++) {
                int idx = (start + offset) % n;
                Slot slot = slots.get(idx);
                SlotHealth h = health.get(slot.name());

                if (h.isPermanentlyDisabled()) { log.debugf("InfiniteLoop: Slot [%s] permanently disabled, skipping.", slot.name()); continue; }
                if (h.isInCooldown()) { log.debugf("InfiniteLoop: Slot [%s] in cooldown (%ds remaining), skipping.", slot.name(), h.cooldownRemainingMs() / 1000); continue; }

                try {
                    long startMs = System.currentTimeMillis();
                    ChatResponse response = ScopedValue.where(LAST_USED_SLOT, slot.name())
                            .call(() -> slot.model().chat(request));
                    long elapsedMs = System.currentTimeMillis() - startMs;
                    if (maxLatencyMs > 0 && elapsedMs > maxLatencyMs) {
                        log.warnf("InfiniteLoop: Slot [%s] responded in %dms (threshold %dms), treating as slow failure.",
                                slot.name(), elapsedMs, maxLatencyMs);
                        h.recordTransientFailure(cooldownTransientSeconds, cooldownBaseSeconds, cooldownMaxSeconds);
                        continue;
                    }
                    h.recordSuccess();
                    currentIndex.set(idx);
                    log.infof("InfiniteLoop: Slot [%s] responded successfully (%dms).", slot.name(), elapsedMs);
                    return response;
                } catch (Throwable t) {
                    classifyAndHandle(slot.name(), h, t);
                }
            }

            if (health.values().stream().allMatch(SlotHealth::isPermanentlyDisabled)) {
                String disabledSlots = health.entrySet().stream()
                    .filter(e -> e.getValue().isPermanentlyDisabled())
                    .map(e -> e.getKey())
                    .collect(Collectors.joining(", "));
                throw new RuntimeException("InfiniteLoop: All providers permanently disabled [" + disabledSlots + "]. "
                    + "Check server status and API keys. Slots will reset on app restart.");
            }

            long shortestMs = Long.MAX_VALUE;
            for (SlotHealth h : health.values()) {
                if (!h.isPermanentlyDisabled()) {
                    long rem = h.cooldownRemainingMs();
                    if (rem > 0 && rem < shortestMs) {
                        shortestMs = rem;
                    }
                }
            }

            if (shortestMs == Long.MAX_VALUE || shortestMs > MAX_COOLDOWN_WAIT_MS) {
                throw new RuntimeException("InfiniteLoop: All providers in cooldown, shortest wait "
                        + shortestMs + "ms exceeds maximum. Giving up.");
            }

            interruptibleSleep(shortestMs, "InfiniteLoop");
        }
    }

    private void classifyAndHandle(String slotName, SlotHealth h, Throwable t) {
        LlmProviderException classified = LlmExceptionClassifier.classify(t);
        if (classified instanceof LlmRateLimitException) {
            String rateMsg = classified.getMessage();
            if (rateMsg != null && rateMsg.toLowerCase().contains("per day")) {
                h.disablePermanently();
                log.errorf("InfiniteLoop: Slot [%s] permanently disabled (daily quota exhausted): %s",
                        slotName, rateMsg);
            } else {
                h.recordRateLimit(1, cooldownMaxSeconds);
                log.infof("InfiniteLoop: Slot [%s] rate-limited, trying next slot. %s", slotName, rateMsg);
            }
        } else if (classified instanceof LlmAuthenticationException) {
            h.disablePermanently();
            log.errorf("InfiniteLoop: Slot [%s] permanently disabled (auth error): %s",
                    slotName, classified.getMessage());
        } else if (classified instanceof LlmServiceUnavailableException) {
            String msg = classified.getMessage();
            if (msg != null && (msg.contains("tool_use_failed") || msg.contains("failed_generation"))) {
                h.disablePermanently();
                log.errorf("InfiniteLoop: Slot [%s] permanently disabled (tool_use_failed): %s", slotName, msg);
            } else {
                h.recordTransientFailure(cooldownTransientSeconds, cooldownBaseSeconds, cooldownMaxSeconds);
                if (h.isPermanentlyDisabled()) {
                    log.errorf("InfiniteLoop: Slot [%s] permanently disabled after %d consecutive transient errors: %s",
                            slotName, MAX_CONSECUTIVE_TRANSIENT_FAILURES, msg);
                } else {
                    log.warnf("InfiniteLoop: Slot [%s] transient error, cooling down %ds. %s",
                            slotName, h.cooldownRemainingMs() / 1000, msg);
                }
            }
        } else if (classified instanceof LlmContentFilterException) {
            throw classified;
        } else {
            String msg = classified.getMessage();
            // Connection refused / timeout are transient — server may restart.
            // Don't permanently disable, just cooldown.
            if (msg != null && (msg.contains("Connection refused") || msg.contains("connect timed out")
                    || msg.contains("timeout") || msg.contains("ECONNREFUSED"))) {
                h.recordTransientFailure(cooldownTransientSeconds, cooldownBaseSeconds, cooldownMaxSeconds);
                if (h.isPermanentlyDisabled()) {
                    log.errorf("InfiniteLoop: Slot [%s] permanently disabled after %d consecutive connection failures: %s",
                            slotName, MAX_CONSECUTIVE_TRANSIENT_FAILURES, msg);
                } else {
                    log.warnf("InfiniteLoop: Slot [%s] connection error, cooling down %ds. %s",
                            slotName, h.cooldownRemainingMs() / 1000, msg);
                }
            } else {
                h.recordUnknownFailure(cooldownBaseSeconds, cooldownMaxSeconds);
                log.warnf("InfiniteLoop: Slot [%s] unclassified error, cooling down %ds. %s",
                        slotName, h.cooldownRemainingMs() / 1000, msg);
            }
        }
    }

    private static void interruptibleSleep(long ms, String label) {
        log.infof("%s: All slots in cooldown, waiting %dms...", label, ms);
        try {
            long remaining = ms;
            while (remaining > 0) {
                Thread.sleep(Math.min(remaining, 5000));
                remaining -= 5000;
                if (WorkspaceContext.SESSION_ID.isBound()) {
                    String sessionId = WorkspaceContext.SESSION_ID.get();
                    if (WorkspaceManager.isSessionHalted(sessionId)) {
                        throw new RuntimeException(label + ": Session " + sessionId + " halted during cooldown wait");
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(label + ": Interrupted while waiting for cooldown", e);
        }
    }

    private static final int MAX_CONSECUTIVE_TRANSIENT_FAILURES = 3;

    private static class SlotHealth {
        private volatile long cooldownUntilEpochMs;
        private volatile int consecutiveFailures;
        private volatile int qualityFailures;
        private volatile int consecutiveTransientFailures;
        private volatile boolean permanentlyDisabled;

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            qualityFailures = 0;
            consecutiveTransientFailures = 0;
            cooldownUntilEpochMs = 0;
        }

        synchronized void recordQualityFailure(int cooldownSeconds) {
            qualityFailures++;
            if (qualityFailures >= QUALITY_FAILURE_THRESHOLD) {
                cooldownUntilEpochMs = System.currentTimeMillis() + (long) cooldownSeconds * 1000;
                qualityFailures = 0;
            }
        }

        synchronized void recordRateLimit(int baseSeconds, int maxSeconds) {
            consecutiveFailures++;
            long delay = (long) baseSeconds * (1L << Math.min(consecutiveFailures - 1, 30));
            if (delay > maxSeconds) delay = maxSeconds;
            cooldownUntilEpochMs = System.currentTimeMillis() + delay * 1000;
        }

        synchronized void disablePermanently() {
            permanentlyDisabled = true;
            cooldownUntilEpochMs = 0;
        }

        synchronized void recordTransientFailure(int transientSeconds, int baseSeconds, int maxSeconds) {
            consecutiveFailures++;
            consecutiveTransientFailures++;
            if (consecutiveTransientFailures >= MAX_CONSECUTIVE_TRANSIENT_FAILURES) {
                permanentlyDisabled = true;
                cooldownUntilEpochMs = 0;
                return;
            }
            // Exponential backoff: base * 2^(failures-1), capped at max
            long delay = (long) baseSeconds * (1L << Math.min(consecutiveTransientFailures - 1, 30));
            if (delay > maxSeconds) delay = maxSeconds;
            if (delay < transientSeconds) delay = transientSeconds; // at least transientSeconds
            cooldownUntilEpochMs = System.currentTimeMillis() + delay * 1000;
        }

        synchronized void recordUnknownFailure(int baseSeconds, int maxSeconds) {
            consecutiveFailures++;
            long delay = (long) baseSeconds * (1L << Math.min(consecutiveFailures - 1, 30));
            if (delay > maxSeconds) delay = maxSeconds;
            cooldownUntilEpochMs = System.currentTimeMillis() + delay * 1000;
        }

        boolean isInCooldown() {
            return System.currentTimeMillis() < cooldownUntilEpochMs;
        }

        boolean isPermanentlyDisabled() {
            return permanentlyDisabled;
        }

        long cooldownRemainingMs() {
            long rem = cooldownUntilEpochMs - System.currentTimeMillis();
            return Math.max(rem, 0);
        }
    }
}
