package com.arcclaw.core.skill;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Repository for {@link AgentSkillEntity} interacting with the underlying SQLite database.
 * Uses direct EntityManager injection for cross-module compatibility.
 */
@ApplicationScoped
public class AgentSkillRepository {

    @Inject
    EntityManager em;

    @Transactional
    public void saveOrUpdate(AgentSkillEntity entity) {
        em.merge(entity);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AgentSkillEntity> findAllSkills() {
        return em.createQuery("SELECT s FROM AgentSkillEntity s", AgentSkillEntity.class).getResultList();
    }

    @Transactional
    public boolean deleteSkillById(String id) {
        AgentSkillEntity entity = em.find(AgentSkillEntity.class, id);
        if (entity != null) {
            em.remove(entity);
            return true;
        }
        return false;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<AgentSkillEntity> findByName(String name) {
        return em.createQuery("SELECT s FROM AgentSkillEntity s WHERE s.name = :name", AgentSkillEntity.class)
                .setParameter("name", name)
                .getResultStream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    @Transactional
    public long deleteAll() {
        return em.createQuery("DELETE FROM AgentSkillEntity").executeUpdate();
    }
}
