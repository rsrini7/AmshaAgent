package com.amsha.selfevolve.store.noop;

import java.util.Collections;
import java.util.List;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;

/**
 * No-op implementation returned when self-evolve is disabled.
 */
public class NoOpDirectiveStore implements DirectiveStorePort {

    @Override
    public int insert(SynthesizedDirective directive) {
        return 0;
    }

    @Override
    public void update(SynthesizedDirective directive) {
        // no-op
    }

    @Override
    public List<SynthesizedDirective> getInjectable(String userId, int limit) {
        return Collections.emptyList();
    }

    @Override
    public List<SynthesizedDirective> getAll(String userId, int limit) {
        return Collections.emptyList();
    }

    @Override
    public SynthesizedDirective getById(String directiveId) {
        return null;
    }

    @Override
    public List<SynthesizedDirective> getByState(DirectiveState state, int limit) {
        return Collections.emptyList();
    }

    @Override
    public boolean delete(String directiveId) {
        return false;
    }

    @Override
    public int purgeExpired(int retentionDays) {
        return 0;
    }
}
