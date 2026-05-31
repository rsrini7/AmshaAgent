package com.arcclaw.core.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.arcclaw.core.agent.reasoning.error.LlmAuthenticationException;
import com.arcclaw.core.agent.reasoning.error.LlmContentFilterException;
import com.arcclaw.core.agent.reasoning.error.LlmExceptionClassifier;
import com.arcclaw.core.agent.reasoning.error.LlmProviderException;
import com.arcclaw.core.agent.reasoning.error.LlmRateLimitException;
import com.arcclaw.core.agent.reasoning.error.LlmServiceUnavailableException;
import com.arcclaw.core.agent.reasoning.error.LlmToolCallException;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.scope.WorkspaceContext;

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

    public MultiModelChatModel(
            String providerName,
            List<ModelEntry> entries,
            int cooldownBaseSeconds,
            int cooldownMaxSeconds,
            int cooldownTransientSeconds) {
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
        return "MultiModel[" + providerName + ": "
                + entries.stream()
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
                    log.debugf(
                            "MultiModel: [%s] %s in cooldown (%ds remaining), skipping.",
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
                    classifyAndHandle(entry.label(), h, t);
                }
            }

            // All entries exhausted
            if (health.values().stream().allMatch(ModelHealth::isPermanentlyDisabled)) {
                throw new RuntimeException("MultiModel: [" + providerName + "] all models permanently disabled");
            }
            // All entries blocked by TPM — request too large for any model in this provider
            if (health.values().stream().allMatch(h -> h.isTpmBlocked() || h.isPermanentlyDisabled())) {
                throw new RuntimeException(
                        "MultiModel: [" + providerName + "] all models blocked — request too large for TPM limits");
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

            interruptibleSleep(shortestMs);
        }
    }

    private void classifyAndHandle(String label, ModelHealth h, Throwable t) {
        LlmProviderException classified = LlmExceptionClassifier.classify(t);
        switch (classified) {
            case LlmRateLimitException r -> handleRateLimit(label, h, r);
            case LlmAuthenticationException a -> {
                h.disablePermanently();
                log.errorf(
                        "MultiModel: [%s] %s permanently disabled (auth error): %s",
                        providerName, label, a.getMessage());
            }
            case LlmServiceUnavailableException u -> handleServiceUnavailable(label, h, u);
            case LlmContentFilterException c -> throw c;
            // Re-throw tool-call errors so the agent loop can extract failed_generation as the answer.
            // Don't cool down slots — "not in request.tools" is a client-side issue, not a provider failure.
            case LlmToolCallException tc -> throw tc;
            default -> {
                h.recordUnknownFailure(cooldownBaseSeconds, cooldownMaxSeconds);
                log.warnf(
                        "MultiModel: [%s] %s unclassified error, cooling down %ds. %s",
                        providerName, label, h.cooldownRemainingMs() / 1000, classified.getMessage());
            }
        }
    }

    private void handleRateLimit(String label, ModelHealth h, LlmRateLimitException e) {
        String msg = e.getMessage();
        if (msg != null && msg.toLowerCase(Locale.ROOT).contains("per day")) {
            h.disablePermanently();
            log.errorf(
                    "MultiModel: [%s] %s permanently disabled (daily quota exhausted): %s", providerName, label, msg);
        } else if (msg != null && msg.toLowerCase(Locale.ROOT).contains("request too large")) {
            h.markTpmBlocked();
            log.infof("MultiModel: [%s] %s request too large for TPM, skipping. %s", providerName, label, msg);
        } else {
            h.recordRateLimit(1, cooldownMaxSeconds);
            log.infof("MultiModel: [%s] %s rate-limited, trying next key. %s", providerName, label, msg);
        }
    }

    private void handleServiceUnavailable(String label, ModelHealth h, LlmServiceUnavailableException e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("tool_use_failed") || msg.contains("failed_generation"))) {
            h.disablePermanently();
            log.errorf(
                    "MultiModel: [%s] %s permanently disabled (tool_use_failed - provider rejected response): %s",
                    providerName, label, msg);
        } else {
            h.recordTransientFailure(cooldownTransientSeconds, cooldownBaseSeconds, cooldownMaxSeconds);
            if (h.isPermanentlyDisabled()) {
                log.errorf(
                        "MultiModel: [%s] %s permanently disabled after %d consecutive transient errors: %s",
                        providerName, label, MAX_CONSECUTIVE_FAILURES, msg);
            } else {
                log.warnf(
                        "MultiModel: [%s] %s transient error, cooling down %ds. %s",
                        providerName, label, h.cooldownRemainingMs() / 1000, msg);
            }
        }
    }

    private void interruptibleSleep(long ms) {
        log.infof("MultiModel: [%s] all models in cooldown, waiting %dms...", providerName, ms);
        try {
            long remaining = ms;
            while (remaining > 0) {
                Thread.sleep(Math.min(remaining, 5000));
                remaining -= 5000;
                if (WorkspaceContext.SESSION_ID.isBound()) {
                    String sessionId = WorkspaceContext.SESSION_ID.get();
                    if (WorkspaceManager.isSessionHalted(sessionId)) {
                        throw new RuntimeException("MultiModel: [" + providerName + "] session " + sessionId
                                + " halted during cooldown wait");
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("MultiModel: [" + providerName + "] interrupted while waiting for cooldown", e);
        }
    }

    private static class ModelHealth {
        private volatile long cooldownUntilEpochMs;
        private volatile int consecutiveFailures;
        private volatile int consecutiveTransientFailures;
        private volatile boolean permanentlyDisabled;
        private volatile boolean tpmBlocked;

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            consecutiveTransientFailures = 0;
            tpmBlocked = false;
            cooldownUntilEpochMs = 0;
        }

        synchronized void markTpmBlocked() {
            tpmBlocked = true;
        }

        boolean isTpmBlocked() {
            return tpmBlocked;
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
