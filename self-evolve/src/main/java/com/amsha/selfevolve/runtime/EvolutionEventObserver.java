package com.amsha.selfevolve.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import com.amsha.core.events.CompactionEvent;
import com.amsha.core.events.ToolExecutionEvent;
import com.amsha.selfevolve.api.EvolutionEventType;

import org.jboss.logging.Logger;

/**
 * CDI async observer that translates core events into evolution trace events.
 * Decouples core module from self-evolve module (core fires events, self-evolve observes).
 */
@ApplicationScoped
public class EvolutionEventObserver {

    private static final Logger log = Logger.getLogger(EvolutionEventObserver.class);

    @Inject
    DefaultSelfEvolveService service;

    /**
     * Observes tool execution events and emits evolution events.
     */
    public void onToolExecution(@ObservesAsync ToolExecutionEvent event) {
        if (!service.isEnabled()) {
            return;
        }
        try {
            EvolutionEventType type =
                    switch (event.type()) {
                        case TOOL_CALL -> EvolutionEventType.TOOL_CALL;
                        case TOOL_RESULT -> EvolutionEventType.TOOL_RESULT;
                        case TOOL_ERROR -> EvolutionEventType.TOOL_ERROR;
                    };
            String content =
                    switch (event.type()) {
                        case TOOL_CALL ->
                            event.params() != null ? event.params().toString() : null;
                        case TOOL_RESULT, TOOL_ERROR -> event.resultOutput();
                    };
            service.emit(
                    event.sessionId(),
                    event.userId(),
                    type,
                    event.role(),
                    content,
                    event.toolName(),
                    event.turnNumber());
        } catch (Exception e) {
            log.warnf("[SelfEvolve] Failed to observe tool event: %s", e.getMessage());
        }
    }

    /**
     * Observes compaction events and emits COMPACTION evolution event.
     */
    public void onCompaction(@ObservesAsync CompactionEvent event) {
        if (!service.isEnabled()) {
            return;
        }
        try {
            String content = String.format(
                    "Compacted %d messages, kept %d. Summary: %s",
                    event.compactedCount(),
                    event.retainedCount(),
                    event.summary() != null
                            ? event.summary()
                                    .substring(0, Math.min(200, event.summary().length()))
                            : "");
            service.emit(event.sessionId(), event.userId(), EvolutionEventType.COMPACTION, null, content, null, null);
        } catch (Exception e) {
            log.warnf("[SelfEvolve] Failed to observe compaction event: %s", e.getMessage());
        }
    }
}
