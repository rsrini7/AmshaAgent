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
    // in CompactionEventBridge.java — update the observer method's finally block only
    public void onCompactionRequested(@ObservesAsync CompactionRequest request) {
        try {
            log.debugf("Compaction bridge received request for session %s", request.sessionId());
            compactionService.compact(request.sessionId(), request.userId());
        } catch (Exception e) {
            log.errorf(e, "Compaction failed for session %s: %s", request.sessionId(), e.getMessage());
        } finally {
            compactionInFlight.set(request.sessionId(), false);  // ← was: compactionInFlight.set(false)
            log.debugf("Compaction bridge released in-flight lock for session %s", request.sessionId());
        }
    }
}
