package com.arcclaw.core.routing;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.arcclaw.core.agent.reasoning.error.LlmAuthenticationException;
import com.arcclaw.core.agent.reasoning.error.LlmExceptionClassifier;
import com.arcclaw.core.agent.reasoning.error.LlmProviderException;
import com.arcclaw.core.agent.reasoning.error.LlmRateLimitException;
import com.arcclaw.core.agent.reasoning.error.LlmServiceUnavailableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

public class InfiniteLoopChatModel implements ChatModel {

    private static final Logger log = Logger.getLogger(InfiniteLoopChatModel.class);
    private static final long MAX_COOLDOWN_WAIT_MS = 300_000L;

    public record Slot(String name, ChatModel model) {}

    private final List<Slot> slots;
    private final Map<String, SlotHealth> health;
    private final AtomicInteger currentIndex;
    private final int cooldownBaseSeconds;
    private final int cooldownMaxSeconds;
    private final int cooldownTransientSeconds;

    public InfiniteLoopChatModel(List<Slot> slots, int cooldownBaseSeconds, int cooldownMaxSeconds, int cooldownTransientSeconds) {
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException("InfiniteLoopChatModel requires at least one slot");
        }
        this.slots = List.copyOf(slots);
        this.health = new ConcurrentHashMap<>();
        for (Slot s : slots) {
            health.put(s.name(), new SlotHealth());
        }
        this.currentIndex = new AtomicInteger(0);
        this.cooldownBaseSeconds = cooldownBaseSeconds;
        this.cooldownMaxSeconds = cooldownMaxSeconds;
        this.cooldownTransientSeconds = cooldownTransientSeconds;
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
                    ChatResponse response = slot.model().chat(request);
                    h.recordSuccess();
                    currentIndex.set(idx);
                    log.infof("InfiniteLoop: Slot [%s] responded successfully.", slot.name());
                    return response;
                } catch (Throwable t) {
                    LlmProviderException classified = LlmExceptionClassifier.classify(t);
                    if (classified instanceof LlmRateLimitException) {
                        h.recordRateLimit(cooldownBaseSeconds, cooldownMaxSeconds);
                        log.warnf("InfiniteLoop: Slot [%s] rate-limited, cooling down %ds. %s",
                                slot.name(), h.cooldownRemainingMs() / 1000, classified.getMessage());
                    } else if (classified instanceof LlmAuthenticationException) {
                        h.disablePermanently();
                        log.errorf("InfiniteLoop: Slot [%s] permanently disabled (auth error): %s",
                                slot.name(), classified.getMessage());
                    } else if (classified instanceof LlmServiceUnavailableException) {
                        h.recordTransientFailure(cooldownTransientSeconds);
                        log.warnf("InfiniteLoop: Slot [%s] transient error, cooling down %ds. %s",
                                slot.name(), cooldownTransientSeconds, classified.getMessage());
                    } else {
                        h.recordUnknownFailure(cooldownBaseSeconds, cooldownMaxSeconds);
                        log.warnf("InfiniteLoop: Slot [%s] unclassified error, cooling down %ds. %s",
                                slot.name(), h.cooldownRemainingMs() / 1000, classified.getMessage());
                    }
                }
            }

            if (health.values().stream().allMatch(SlotHealth::isPermanentlyDisabled)) {
                throw new RuntimeException("InfiniteLoop: All providers permanently disabled (auth errors)");
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

            log.debugf("InfiniteLoop: All slots in cooldown, waiting %dms...", shortestMs);
            try {
                Thread.sleep(shortestMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("InfiniteLoop: Interrupted while waiting for cooldown", e);
            }
        }
    }

    private static class SlotHealth {
        private volatile long cooldownUntilEpochMs;
        private volatile int consecutiveFailures;
        private volatile boolean permanentlyDisabled;

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            cooldownUntilEpochMs = 0;
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

        synchronized void recordTransientFailure(int transientSeconds) {
            consecutiveFailures++;
            cooldownUntilEpochMs = System.currentTimeMillis() + (long) transientSeconds * 1000;
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
