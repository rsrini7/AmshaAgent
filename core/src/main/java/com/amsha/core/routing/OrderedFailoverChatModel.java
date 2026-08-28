package com.amsha.core.routing;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.core.agent.reasoning.error.LlmErrorClassificationService;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.MultimodalCapable;
import com.amsha.llm.api.MultimodalRequests;
import com.amsha.llm.api.StrictJsonSchemaCapable;
import com.amsha.llm.api.StructuredOutputCapabilities;
import com.amsha.llm.api.StructuredSchemaRequests;
import com.amsha.llm.api.error.LlmAuthenticationException;
import com.amsha.llm.api.error.LlmContentFilterException;
import com.amsha.llm.api.error.LlmMalformedResponseException;
import com.amsha.llm.api.error.LlmModelExhaustedException;
import com.amsha.llm.api.error.LlmProviderException;
import com.amsha.llm.api.error.LlmRateLimitException;
import com.amsha.llm.api.error.LlmServiceUnavailableException;
import com.amsha.llm.api.error.LlmToolCallException;

import org.jboss.logging.Logger;

/**
 * Sticky-primary, failover-only wrapper for an assigned model-profile chain.
 *
 * <p>Unlike {@link MultiModelChatModel}, this wrapper does NOT round-robin successful requests and
 * does NOT span multiple user accounts or providers: every entry belongs to the same assigned
 * route. The first eligible profile is attempted first for each new request; later entries are
 * failover candidates only. A retryable provider/model failure may advance within the chain but
 * must never select another user connection, system credential, or model merely because it exists.
 * Authentication/validation/policy failures permanently disable the affected profile and are
 * audited; they never silently broaden routing.</p>
 *
 * <p>Health is keyed by route + profile revision so credential rotation or profile correction
 * clears stale permanent-disable state.</p>
 */
public class OrderedFailoverChatModel implements ChatModel, StrictJsonSchemaCapable, MultimodalCapable {

    private static final Logger log = Logger.getLogger(OrderedFailoverChatModel.class);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    public record Entry(
            String label, ChatModel model, boolean advertisesStrictSchema, MultimodalCapability multimodalCapability) {

        public Entry(String label, ChatModel model, boolean advertisesStrictSchema) {
            this(label, model, advertisesStrictSchema, MultimodalCapabilities.resolveCapability(model));
        }

        public Entry(String label, ChatModel model) {
            this(label, model, false, MultimodalCapabilities.resolveCapability(model));
        }
    }

    private final String routeKey;
    private final List<Entry> entries;
    private final Set<Capability> compositeCapabilities;
    private final MultimodalCapability compositeMultimodalCapability;
    private final Map<String, Health> health;
    private final int cooldownBaseSeconds;
    private final int cooldownMaxSeconds;
    private final int cooldownTransientSeconds;

    public OrderedFailoverChatModel(
            String routeKey,
            List<Entry> entries,
            int cooldownBaseSeconds,
            int cooldownMaxSeconds,
            int cooldownTransientSeconds) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("OrderedFailoverChatModel requires at least one entry");
        }
        this.routeKey = routeKey;
        this.entries = List.copyOf(entries);
        this.compositeCapabilities = StructuredOutputCapabilities.intersectionOf(
                this.entries.stream().map(Entry::model).toList());
        this.compositeMultimodalCapability = MultimodalCapabilities.intersect(
                this.entries.stream().map(Entry::multimodalCapability).toList());
        this.health = new ConcurrentHashMap<>();
        for (Entry e : entries) {
            health.put(e.label(), new Health());
        }
        this.cooldownBaseSeconds = cooldownBaseSeconds;
        this.cooldownMaxSeconds = cooldownMaxSeconds;
        this.cooldownTransientSeconds = cooldownTransientSeconds;
    }

    public OrderedFailoverChatModel(String routeKey, List<Entry> entries, int cooldownTransientSeconds) {
        this(routeKey, entries, 10, 60, cooldownTransientSeconds);
    }

    @Override
    public MultimodalCapability multimodalCapability() {
        return compositeMultimodalCapability;
    }

    /**
     * Safe intersection of all candidates' capabilities, computed once at
     * composition time. Never upgraded mid-request when failover advances to
     * another candidate; health, cooldown, and permanent-disable states never
     * change the reported set.
     */
    @Override
    public Set<Capability> supportedCapabilities() {
        return compositeCapabilities;
    }

    @Override
    public boolean supportsStrictJsonSchema() {
        return entries.stream().allMatch(OrderedFailoverChatModel::candidateSupportsStrictSchema);
    }

    private static boolean candidateSupportsStrictSchema(Entry entry) {
        return entry.advertisesStrictSchema()
                || (entry.model() instanceof StrictJsonSchemaCapable capable && capable.supportsStrictJsonSchema());
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int n = entries.size();
        // Replay invariant: once a candidate fails while serving a schema-bearing
        // request, every later candidate receives the downgraded (schema-less)
        // request — recomputed per candidate via withoutNativeJsonSchema, never a
        // verbatim replay of an already-failed schema request.
        boolean schemaRejected = false;
        LlmMalformedResponseException schemaRejection = null;
        for (int offset = 0; offset < n; offset++) {
            Entry entry = entries.get(offset);
            Health h = health.get(entry.label());
            if (h.isPermanentlyDisabled()) {
                log.debugf("OrderedFailover[%s] %s permanently disabled, skipping.", routeKey, entry.label());
                continue;
            }
            if (h.isInCooldown()) {
                log.debugf("OrderedFailover[%s] %s in cooldown, skipping.", routeKey, entry.label());
                continue;
            }
            ChatRequest candidateReq =
                    MultimodalRequests.forCandidate(request, entry.multimodalCapability(), entry.label());
            ChatRequest outbound = schemaRejected
                    ? StructuredSchemaRequests.withoutNativeJsonSchema(candidateReq)
                    : StructuredSchemaRequests.forCandidate(candidateReq, candidateSupportsStrictSchema(entry));
            boolean schemaAttached = StructuredSchemaRequests.hasNativeJsonSchema(outbound);
            try {
                ChatResponse response = entry.model().chat(outbound);
                h.recordSuccess();
                return response;
            } catch (Throwable t) {
                if (schemaAttached) {
                    // A schema-bearing failure must advance (downgraded), never abort
                    // the chain: the next candidate gets the stripped request.
                    schemaRejected = true;
                }
                try {
                    classifyAndHandle(entry.label(), h, t);
                } catch (LlmMalformedResponseException mre) {
                    if (!schemaAttached) {
                        throw mre;
                    }
                    schemaRejection = mre;
                    log.debugf(
                            "OrderedFailover[%s] %s rejected native schema; continuing without it.",
                            routeKey, entry.label());
                }
            }
        }
        if (schemaRejection != null) {
            throw schemaRejection;
        }
        throw new LlmModelExhaustedException("OrderedFailover[" + routeKey + "] all profiles exhausted or disabled");
    }

    private void classifyAndHandle(String label, Health h, Throwable t) {
        LlmProviderException classified = LlmErrorClassificationService.classify(t);
        switch (classified) {
            case LlmRateLimitException r -> handleRateLimit(label, h, r);
            case LlmAuthenticationException a -> {
                h.disablePermanently();
                log.errorf(
                        "OrderedFailover[%s] %s permanently disabled (auth error): %s",
                        routeKey, label, a.getMessage());
            }
            case LlmServiceUnavailableException u -> handleServiceUnavailable(label, h, u);
            case LlmContentFilterException c -> throw c;
            case LlmToolCallException tc -> throw tc;
            case LlmMalformedResponseException mr -> throw mr;
            default -> {
                h.recordUnknownFailure(cooldownBaseSeconds, cooldownMaxSeconds);
                log.warnf(
                        "OrderedFailover[%s] %s unclassified error, cooling down. %s",
                        routeKey, label, classified.getMessage());
            }
        }
    }

    private void handleRateLimit(String label, Health h, LlmRateLimitException e) {
        String msg = e.getMessage();
        if (msg != null && msg.toLowerCase(Locale.ROOT).contains("per day")) {
            h.disablePermanently();
        } else {
            h.recordRateLimit(1, cooldownMaxSeconds);
        }
    }

    private void handleServiceUnavailable(String label, Health h, LlmServiceUnavailableException e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("tool_use_failed") || msg.contains("failed_generation"))) {
            h.disablePermanently();
        } else {
            h.recordTransientFailure(cooldownTransientSeconds, cooldownBaseSeconds, cooldownMaxSeconds);
        }
    }

    // holder to avoid extra import noise: LlmContentFilterException referenced via alias
    private static final class Health {
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
            cooldownUntilEpochMs = System.currentTimeMillis() + Math.min(delay, maxSeconds) * 1000L;
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
            cooldownUntilEpochMs =
                    System.currentTimeMillis() + Math.max(Math.min(delay, maxSeconds), transientSeconds) * 1000L;
        }

        synchronized void recordUnknownFailure(int baseSeconds, int maxSeconds) {
            consecutiveFailures++;
            long delay = (long) baseSeconds * (1L << Math.min(consecutiveFailures - 1, 30));
            cooldownUntilEpochMs = System.currentTimeMillis() + Math.min(delay, maxSeconds) * 1000L;
        }

        boolean isInCooldown() {
            return System.currentTimeMillis() < cooldownUntilEpochMs;
        }

        boolean isPermanentlyDisabled() {
            return permanentlyDisabled;
        }
    }
}
