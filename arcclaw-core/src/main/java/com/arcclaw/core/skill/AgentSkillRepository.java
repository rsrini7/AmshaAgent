package com.arcclaw.core.skill;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

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
    public void saveOrUpdate(AgentSkillEntity entity) {
        skillStore.save(toDto(entity));
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AgentSkillEntity> findAllSkills() {
        return skillStore.findAll().stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public boolean deleteSkillById(String id) {
        return skillStore.deleteById(id);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<AgentSkillEntity> findByName(String name) {
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

    private SkillDto toDto(AgentSkillEntity entity) {
        if (entity == null) return null;
        return new SkillDto(
            entity.getId(),
            entity.getName(),
            entity.getDefinitionYaml(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
