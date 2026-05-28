package com.arcclaw.core.domain.model;

import java.time.Instant;

import com.arcclaw.persistence.api.SkillDto;

public final class AgentSkillRecord implements AgentSkill {

    private String id;
    private String name;
    private String definitionYaml;
    private Instant createdAt;
    private Instant updatedAt;

    public AgentSkillRecord() {}

    public static AgentSkillRecord fromDto(SkillDto dto) {
        AgentSkillRecord r = new AgentSkillRecord();
        r.id = dto.id();
        r.name = dto.name();
        r.definitionYaml = dto.definitionYaml();
        r.createdAt = dto.createdAt();
        r.updatedAt = dto.updatedAt();
        return r;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDefinitionYaml() {
        return definitionYaml;
    }

    @Override
    public void setDefinitionYaml(String definitionYaml) {
        this.definitionYaml = definitionYaml;
    }

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
