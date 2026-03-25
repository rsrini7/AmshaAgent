package com.arcclaw.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import com.arcclaw.core.compaction.CompactionInFlightFlag;
import com.arcclaw.core.compaction.CompactionRequest;
import org.jboss.logging.Logger;

/**
 * Bridges the asynchronous {@link CompactionRequest} event to the {@link CompactionService}.
 * <p>
 * Separates event-driven infrastructure from core business logic (SRP).
 */
@ApplicationScoped
public class CompactionEventBridge {

    private static final Logger log = Logger.getLogger(CompactionEventBridge.class);

    @Inject
    CompactionService compactionService;

    @Inject
    CompactionInFlightFlag compactionInFlight;

    /**
     * Listens for compaction requests and executes the service logic.
     * <p>
     * Replaces the event observer that was previously embedded in {@code ContextCompactionService}.
     */
    public void onCompactionRequested(@ObservesAsync CompactionRequest request) {
        try {
            log.debugf("Compaction bridge received request for session %s", request.sessionId());
            compactionService.compact(request.sessionId(), request.userId());
        } catch (Exception e) {
            log.errorf("Compaction failed for session %s: %s", request.sessionId(), e.getMessage());
            log.debug("Full compaction failure stack trace:", e);
        } finally {
            // Always clear the flag so the next request can proceed
            compactionInFlight.set(false);
            log.debugf("Compaction bridge released in-flight lock for session %s", request.sessionId());
        }
    }
}
