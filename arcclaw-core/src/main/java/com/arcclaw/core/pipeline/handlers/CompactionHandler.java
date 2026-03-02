package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.state.ContextCompactionService;
import jakarta.enterprise.context.ApplicationScoped;
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

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        String sessionId = context.getSessionId()
                .orElseThrow(() -> new IllegalStateException("Session ID must be resolved before compaction."));
        String userId = context.getUser().platformId();

        try {
            boolean compacted = compactionService.compactIfNeeded(sessionId, userId);
            if (compacted) {
                log.debugf("Compaction triggered for session %s", sessionId);
            }
        } catch (Exception e) {
            // Log but don't fail the pipeline; compaction is an optimization
            log.warnf(e, "Compaction failed for session %s during pipeline execution", sessionId);
        }

        return HandleResult.CONTINUE;
    }
}
