package com.arcclaw.core.skill;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.domain.model.AgentSkill;
import com.arcclaw.persistence.api.SkillDto;
import com.arcclaw.persistence.api.SkillStorePort;
import io.quarkus.arc.Unremovable;

/**
 * Repository for {@link AgentSkillEntity} refactored to delegate to {@link SkillStorePort}.
 * §1.2: Zero EntityManager imports, zero JPQL.
 */
@ApplicationScoped
@Unremovable
public class AgentSkillRepository {

    @Inject
    SkillStorePort skillStore;

    @Transactional
    public void saveOrUpdate(AgentSkill skill) {
        skillStore.save(toDto(skill));
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AgentSkill> findAllSkills() {
        return skillStore.findAll().stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public boolean deleteSkillById(String id) {
        return skillStore.deleteById(id);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<AgentSkill> findByName(String name) {
        return skillStore.findByName(name).map(this::toEntity);
    }

    @Transactional
    public long deleteAll() {
        return skillStore.deleteAll();
    }

    private AgentSkillEntity toEntity(SkillDto dto) {
        if (dto == null) return null;
        return new AgentSkillEntity(
            dto.id(),
            dto.name(),
            dto.definitionYaml(),
            dto.createdAt(),
            dto.updatedAt()
        );
    }

    private SkillDto toDto(AgentSkill skill) {
        if (skill == null) return null;
        return new SkillDto(
            skill.getId(),
            skill.getName(),
            skill.getDefinitionYaml(),
            skill.getCreatedAt(),
            skill.getUpdatedAt()
        );
    }
}
