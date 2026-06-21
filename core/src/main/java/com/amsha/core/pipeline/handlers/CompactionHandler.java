package com.amsha.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.common.util.LogUtil;
import com.amsha.core.compaction.CompactionInFlightFlag;
import com.amsha.core.compaction.CompactionRequest;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.pipeline.HandleResult;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.MessageHandler;
import com.amsha.core.state.CompactionEventBridge;
import com.amsha.core.state.CompactionService;

import org.jboss.logging.Logger;

@ApplicationScoped
public class CompactionHandler implements MessageHandler {

    private static final Logger log = Logger.getLogger(CompactionHandler.class);

    @Inject
    CompactionService compactionService;

    @Inject
    DomainEventBus eventBus;

    @Inject
    CompactionInFlightFlag compactionInFlight;

    @Inject
    CompactionEventBridge compactionEventBridge;

    @Inject
    RateLimitTracker rateLimitTracker;

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        String sessionId = context.getSessionId().orElse(null);
        if (sessionId == null) {
            log.debug("CompactionHandler session not yet resolved, skipping");
            return HandleResult.CONTINUE;
        }
        String userId = context.getUser() != null ? context.getUser().platformId() : "unknown";

        if (compactionService.shouldCompact(sessionId)) {

            // ── Skip compaction if provider is currently throttled.
            // Compaction calls the LLM for its summary - firing it while
            // rate-limited causes a double-Groq-hit that produces Done. in 1 token.
            if (rateLimitTracker.isAnyBackingOff()) {
                log.debugf(
                        "CompactionHandler: skipping compaction for session %s - "
                                + "LLM provider is backing off after rate-limit",
                        sessionId);
                return HandleResult.CONTINUE;
            }

            if (compactionInFlight.compareAndSet(sessionId, false, true)) {
                compactionEventBridge.registerLatch(sessionId);
                try {
                    eventBus.publishAsync(EventTypeCatalog.COMPACTION_REQUEST, new CompactionRequest(sessionId, userId))
                            .exceptionally(ex -> {
                                compactionInFlight.set(sessionId, false);
                                compactionEventBridge.releaseLatch(sessionId);
                                log.errorf(
                                        "Async compaction failed for session %s: %s",
                                        sessionId, LogUtil.rootMessage(ex));
                                return null;
                            });
                    log.debugf("Async compaction triggered for session %s", sessionId);
                } catch (Throwable t) {
                    compactionInFlight.set(sessionId, false);
                    compactionEventBridge.releaseLatch(sessionId);
                    throw t;
                }
            } else {
                log.debugf("Compaction already in-flight for session %s, skipping", sessionId);
            }
        }
        return HandleResult.CONTINUE;
    }
}
