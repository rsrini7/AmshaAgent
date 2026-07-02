package com.amsha.selfevolve.store.noop;

import java.util.Collections;
import java.util.List;

import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.store.EvolutionEventStorePort;

/**
 * No-op implementation returned when self-evolve is disabled.
 * All operations are silent no-ops — zero overhead.
 */
public class NoOpEvolutionEventStore implements EvolutionEventStorePort {

    @Override
    public int insertBatch(List<EvolutionEvent> events) {
        return 0;
    }

    @Override
    public List<EvolutionEvent> getBySession(String sessionId, int limit) {
        return Collections.emptyList();
    }

    @Override
    public List<EvolutionEvent> getByUser(String userId, int limit) {
        return Collections.emptyList();
    }

    @Override
    public int purgeExpired(int retentionDays) {
        return 0;
    }
}
