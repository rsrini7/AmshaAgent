package com.arcclaw.persistence.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

import com.arcclaw.core.domain.model.AgentSkill;

/**
 * Persistent script entity backed by the {@code agent_skill} table.
 * Contains the raw YAML + text block shipped by the user or pre-loaded.
 */
@Entity
@Table(name = "agent_skill")
public class AgentSkillEntity implements AgentSkill {

    @Id
    private String id;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String definitionYaml;

    private Instant createdAt;

    private Instant updatedAt;

    protected AgentSkillEntity() {
        // Required by JPA framework
    }

    public AgentSkillEntity(String id, String name, String definitionYaml, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.definitionYaml = definitionYaml;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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
