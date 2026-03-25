package com.arcclaw.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.compaction.CompactionRequest;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScoped;
import com.arcclaw.core.state.CompactionService;
import org.jboss.logging.Logger;

/**
 * compacts conversation history if necessary before passing to the agent.
 */
@ApplicationScoped
public class CompactionHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(CompactionHandler.class);

    @Inject
    CompactionService compactionService;

    @Inject
    Event<CompactionRequest> compactionEvent;

    @Inject
    @WorkspaceScoped
    CompactionInFlightFlag compactionInFlight;

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        if (!WorkspaceContext.SESSION_ID.isBound()) {
            log.debug("CompactionHandler: WorkspaceScoped context not active, deferring compaction check");
            return HandleResult.CONTINUE;
        }

        String sessionId = context.getSessionId()
                .orElseThrow(() -> new IllegalStateException("Session ID must be resolved before compaction."));
        String userId = context.getUser().platformId();

        if (compactionService.shouldCompact(sessionId)) {
            if (compactionInFlight.compareAndSet(false, true)) {
                // Bridge (CompactionEventBridge) is responsible for releasing the lock in its finally block.
                compactionEvent.fireAsync(new CompactionRequest(sessionId, userId));
                log.debugf("Async compaction triggered for session %s", sessionId);
            } else {
                log.debugf("Compaction already in-flight for session %s — skipping trigger", sessionId);
            }
        }

        return HandleResult.CONTINUE;
    }
}
