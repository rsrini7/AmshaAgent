package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.compaction.CompactionRequest;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.state.ContextCompactionService;
import com.arcclaw.core.scope.WorkspaceScoped;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * compacts conversation history if necessary before passing to the agent.
 */
@ApplicationScoped
public class CompactionHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(CompactionHandler.class);

    @Inject
    ContextCompactionService compactionService;

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
        String sessionId = context.getSessionId()
                .orElseThrow(() -> new IllegalStateException("Session ID must be resolved before compaction."));
        String userId = context.getUser().platformId();

        if (compactionService.shouldCompact(sessionId)) {
            if (compactionInFlight.compareAndSet(false, true)) {
                compactionEvent.fireAsync(new CompactionRequest(sessionId, userId))
                        .exceptionally(ex -> {
                            log.errorf(ex, "Async compaction event delivery failed for session %s", sessionId);
                            compactionInFlight.set(false);
                            return null;
                        });
                log.debugf("Async compaction triggered for session %s", sessionId);
            } else {
                log.debugf("Compaction already in-flight for session %s — skipping trigger", sessionId);
            }
        }

        return HandleResult.CONTINUE;
    }
}
