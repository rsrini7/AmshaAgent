package com.arcclaw.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.compaction.CompactionRequest;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.state.CompactionEventBridge;
import com.arcclaw.core.state.CompactionService;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CompactionHandler implements MessageHandler {

    private static final Logger log = Logger.getLogger(CompactionHandler.class);

    @Inject CompactionService compactionService;
    @Inject Event<CompactionRequest> compactionEvent;
    @Inject CompactionInFlightFlag compactionInFlight;
    @Inject CompactionEventBridge compactionEventBridge;
    @Inject RateLimitTracker rateLimitTracker;

    @Override
    public int priority() { return 60; }

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
            // Compaction calls the LLM for its summary — firing it while
            // rate-limited causes a double-Groq-hit that produces Done. in 1 token.
            if (rateLimitTracker.isBackingOff()) {
                log.debugf("CompactionHandler: skipping compaction for session %s — " +
                        "LLM provider is backing off after rate-limit", sessionId);
                return HandleResult.CONTINUE;
            }

            if (compactionInFlight.compareAndSet(sessionId, false, true)) {
                compactionEventBridge.registerLatch(sessionId);
                compactionEvent.fireAsync(new CompactionRequest(sessionId, userId));
                log.debugf("Async compaction triggered for session %s", sessionId);
            } else {
                log.debugf("Compaction already in-flight for session %s, skipping", sessionId);
            }
        }
        return HandleResult.CONTINUE;
    }
}