package com.arcclaw.persistence.sqlite;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.SkillDto;
import com.arcclaw.persistence.api.SkillStorePort;
import com.arcclaw.persistence.sqlite.entity.AgentSkillEntity;
import io.quarkus.arc.Unremovable;

/**
 * SQLite implementation of SkillStorePort.
 * §1.2: All JPQL moved from core to here.
 */
@ApplicationScoped
@Unremovable
public class SqliteSkillStoreAdapter implements SkillStorePort {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public void save(SkillDto dto) {
        AgentSkillEntity entity = new AgentSkillEntity(
            dto.id(),
            dto.name(),
            dto.definitionYaml(),
            dto.createdAt(),
            dto.updatedAt()
        );
        em.merge(entity);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<SkillDto> findAll() {
        return em.createQuery("SELECT s FROM AgentSkillEntity s", AgentSkillEntity.class)
                .getResultList()
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<SkillDto> findByName(String name) {
        return em.createQuery("SELECT s FROM AgentSkillEntity s WHERE s.name = :name", AgentSkillEntity.class)
                .setParameter("name", name)
                .getResultStream()
                .findFirst()
                .map(this::toDto);
    }

    @Override
    @Transactional
    public boolean deleteById(String id) {
        AgentSkillEntity entity = em.find(AgentSkillEntity.class, id);
        if (entity != null) {
            em.remove(entity);
            return true;
        }
        return false;
    }

    @Override
    @Transactional
    public long deleteAll() {
        return em.createQuery("DELETE FROM AgentSkillEntity").executeUpdate();
    }

    private SkillDto toDto(AgentSkillEntity entity) {
        return new SkillDto(
            entity.getId(),
            entity.getName(),
            entity.getDefinitionYaml(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}

