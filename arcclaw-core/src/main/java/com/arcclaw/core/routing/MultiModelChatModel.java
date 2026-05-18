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

/**
 * Wraps multiple ChatModel instances (different API keys and/or models from the same provider)
 * with per-model health tracking and round-robin rotation. When one key/model fails,
 * rotates to the next healthy one.
 *
 * <p>Used by provider factories to support comma-separated API keys and model names.
 * The InfiniteLoopChatModel sees this as a single slot.
 */
public class MultiModelChatModel implements ChatModel {

    private static final Logger log = Logger.getLogger(MultiModelChatModel.class);
    private static final long MAX_COOLDOWN_WAIT_MS = 120_000L;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    public record ModelEntry(String label, ChatModel model) {}

    private final String providerName;
    private final List<ModelEntry> entries;
    private final Map<String, ModelHealth> health;
    private final AtomicInteger currentIndex;
    private final int cooldownBaseSeconds;
    private final int cooldownMaxSeconds;
    private final int cooldownTransientSeconds;

    public MultiModelChatModel(String providerName, List<ModelEntry> entries,
                               int cooldownBaseSeconds, int cooldownMaxSeconds, int cooldownTransientSeconds) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("MultiModelChatModel requires at least one entry");
        }
        this.providerName = providerName;
        this.entries = List.copyOf(entries);
        this.health = new ConcurrentHashMap<>();
        for (ModelEntry e : entries) {
            health.put(e.label(), new ModelHealth());
        }
        this.currentIndex = new AtomicInteger(0);
        this.cooldownBaseSeconds = cooldownBaseSeconds;
        this.cooldownMaxSeconds = cooldownMaxSeconds;
        this.cooldownTransientSeconds = cooldownTransientSeconds;
    }

    public MultiModelChatModel(String providerName, List<ModelEntry> entries, int cooldownTransientSeconds) {
        this(providerName, entries, 10, 60, cooldownTransientSeconds);
    }

    @Override
    public String toString() {
        return "MultiModel[" + providerName + ": " + entries.stream()
                .map(ModelEntry::label)
                .reduce((a, b) -> a + ", " + b)
                .orElse("none") + "]";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int n = entries.size();

        while (true) {
            int start = currentIndex.updateAndGet(i -> (i + 1) % n);

            for (int offset = 0; offset < n; offset++) {
                int idx = (start + offset) % n;
                ModelEntry entry = entries.get(idx);
                ModelHealth h = health.get(entry.label());

                if (h.isPermanentlyDisabled()) {
                    log.debugf("MultiModel: [%s] %s permanently disabled, skipping.", providerName, entry.label());
                    continue;
                }
                if (h.isInCooldown()) {
                    log.debugf("MultiModel: [%s] %s in cooldown (%ds remaining), skipping.",
                            providerName, entry.label(), h.cooldownRemainingMs() / 1000);
                    continue;
                }

                try {
                    long startMs = System.currentTimeMillis();
                    ChatResponse response = entry.model().chat(request);
                    long elapsedMs = System.currentTimeMillis() - startMs;
                    h.recordSuccess();
                    currentIndex.set(idx);
                    log.infof("MultiModel: [%s] served by %s (%dms)", providerName, entry.label(), elapsedMs);
                    return response;
                } catch (Throwable t) {
                    LlmProviderException classified = LlmExceptionClassifier.classify(t);
                    if (classified instanceof LlmRateLimitException) {
                        h.recordRateLimit(cooldownBaseSeconds, cooldownMaxSeconds);
                        log.warnf("MultiModel: [%s] %s rate-limited, cooling down %ds. %s",
                                providerName, entry.label(), h.cooldownRemainingMs() / 1000, classified.getMessage());
                    } else if (classified instanceof LlmAuthenticationException) {
                        h.disablePermanently();
                        log.errorf("MultiModel: [%s] %s permanently disabled (auth error): %s",
                                providerName, entry.label(), classified.getMessage());
                    } else if (classified instanceof LlmServiceUnavailableException) {
                        h.recordTransientFailure(cooldownTransientSeconds, cooldownBaseSeconds, cooldownMaxSeconds);
                        if (h.isPermanentlyDisabled()) {
                            log.errorf("MultiModel: [%s] %s permanently disabled after %d consecutive transient errors: %s",
                                    providerName, entry.label(), MAX_CONSECUTIVE_FAILURES, classified.getMessage());
                        } else {
                            log.warnf("MultiModel: [%s] %s transient error, cooling down %ds. %s",
                                    providerName, entry.label(), h.cooldownRemainingMs() / 1000, classified.getMessage());
                        }
                    } else {
                        h.recordUnknownFailure(cooldownBaseSeconds, cooldownMaxSeconds);
                        log.warnf("MultiModel: [%s] %s unclassified error, cooling down %ds. %s",
                                providerName, entry.label(), h.cooldownRemainingMs() / 1000, classified.getMessage());
                    }
                }
            }

            // All entries exhausted
            if (health.values().stream().allMatch(ModelHealth::isPermanentlyDisabled)) {
                throw new RuntimeException("MultiModel: [" + providerName + "] all models permanently disabled");
            }

            long shortestMs = Long.MAX_VALUE;
            for (ModelHealth h : health.values()) {
                if (!h.isPermanentlyDisabled()) {
                    long rem = h.cooldownRemainingMs();
                    if (rem > 0 && rem < shortestMs) {
                        shortestMs = rem;
                    }
                }
            }

            if (shortestMs == Long.MAX_VALUE || shortestMs > MAX_COOLDOWN_WAIT_MS) {
                throw new RuntimeException("MultiModel: [" + providerName + "] all models in cooldown, shortest wait "
                        + shortestMs + "ms exceeds maximum");
            }

            log.infof("MultiModel: [%s] all models in cooldown, waiting %dms...", providerName, shortestMs);
            try {
                Thread.sleep(shortestMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("MultiModel: [" + providerName + "] interrupted while waiting for cooldown", e);
            }
        }
    }

    private static class ModelHealth {
        private volatile long cooldownUntilEpochMs;
        private volatile int consecutiveFailures;
        private volatile int consecutiveTransientFailures;
        private volatile boolean permanentlyDisabled;

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            consecutiveTransientFailures = 0;
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

        synchronized void recordTransientFailure(int transientSeconds, int baseSeconds, int maxSeconds) {
            consecutiveFailures++;
            consecutiveTransientFailures++;
            if (consecutiveTransientFailures >= MAX_CONSECUTIVE_FAILURES) {
                permanentlyDisabled = true;
                cooldownUntilEpochMs = 0;
                return;
            }
            long delay = (long) baseSeconds * (1L << Math.min(consecutiveTransientFailures - 1, 30));
            if (delay > maxSeconds) delay = maxSeconds;
            if (delay < transientSeconds) delay = transientSeconds;
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
