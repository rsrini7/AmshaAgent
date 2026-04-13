package com.arcclaw.core.skill;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Persistent script entity backed by the {@code agent_skill} SQLite table.
 * <p>
 * Contains the raw YAML + text block shipped by the user or pre-loaded.
 */
@Entity
@Table(name = "agent_skill")
public class AgentSkillEntity {

    @Id
    private String id;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String definitionYaml;

    private Instant createdAt;

    private Instant updatedAt;

    protected AgentSkillEntity() {
        // Required by Hibernate
    }

    public AgentSkillEntity(String id, String name, String definitionYaml, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.definitionYaml = definitionYaml;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDefinitionYaml() {
        return definitionYaml;
    }

    public void setDefinitionYaml(String definitionYaml) {
        this.definitionYaml = definitionYaml;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
