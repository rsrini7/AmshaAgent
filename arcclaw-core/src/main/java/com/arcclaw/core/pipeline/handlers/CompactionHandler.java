// arcclaw-core/src/main/java/com/arcclaw/core/pipeline/handlers/CompactionHandler.java
package com.arcclaw.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.compaction.CompactionRequest;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.state.CompactionService;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CompactionHandler implements MessageHandler {

    private static final Logger log = Logger.getLogger(CompactionHandler.class);

    @Inject CompactionService compactionService;
    @Inject Event<CompactionRequest> compactionEvent;
    @Inject CompactionInFlightFlag compactionInFlight;  // ← no @WorkspaceScoped qualifier

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
            if (compactionInFlight.compareAndSet(sessionId, false, true)) {
                compactionEvent.fireAsync(new CompactionRequest(sessionId, userId));
                log.debugf("Async compaction triggered for session %s", sessionId);
            } else {
                log.debugf("Compaction already in-flight for session %s, skipping", sessionId);
            }
        }
        return HandleResult.CONTINUE;
    }
}