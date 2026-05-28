package com.arcclaw.core.domain.model;

import java.time.Instant;

public interface AgentSkill {

    String getId();

    String getName();

    String getDefinitionYaml();

    void setDefinitionYaml(String definitionYaml);

    Instant getCreatedAt();

    Instant getUpdatedAt();

    void setUpdatedAt(Instant updatedAt);

    // ── Wither methods ────────────────────────────────────────────────────

    default AgentSkill withDefinitionYaml(String y) {
        setDefinitionYaml(y);
        return this;
    }

    default AgentSkill withUpdatedAt(Instant t) {
        setUpdatedAt(t);
        return this;
    }
}
