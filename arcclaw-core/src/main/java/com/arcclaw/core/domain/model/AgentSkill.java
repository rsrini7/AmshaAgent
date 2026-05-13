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
}
