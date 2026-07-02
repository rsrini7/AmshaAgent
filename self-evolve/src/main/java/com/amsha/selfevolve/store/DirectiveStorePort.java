package com.amsha.selfevolve.store;

import java.util.List;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;

/**
 * Persistence port for synthesized directives.
 */
public interface DirectiveStorePort {

    /**
     * Persists a new directive. Uses INSERT OR IGNORE for idempotency.
     *
     * @return number of directives actually persisted
     */
    int insert(SynthesizedDirective directive);

    /**
     * Updates a directive's state and evaluation counters.
     */
    void update(SynthesizedDirective directive);

    /**
     * Retrieves all injectable directives (ACTIVE + SHADOW) for a user, ordered by confidence desc.
     *
     * @param limit max directives to return
     */
    List<SynthesizedDirective> getInjectable(String userId, int limit);

    /**
     * Retrieves all directives for a user, regardless of state.
     */
    List<SynthesizedDirective> getAll(String userId, int limit);

    /**
     * Retrieves a directive by ID.
     */
    SynthesizedDirective getById(String directiveId);

    /**
     * Retrieves all directives in a specific state.
     */
    List<SynthesizedDirective> getByState(DirectiveState state, int limit);

    /**
     * Deletes a directive by ID (admin action).
     */
    boolean delete(String directiveId);

    /**
     * Purges DISABLED directives older than the specified retention days.
     */
    int purgeExpired(int retentionDays);
}
