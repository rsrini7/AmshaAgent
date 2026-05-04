package com.arcclaw.persistence.sqlite;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;

/**
 * Handles core JPA operations for AgentCheckpoint entities.
 */
@ApplicationScoped
public class CheckpointRepository {

    private final EntityManager em;

    @Inject
    public CheckpointRepository(EntityManager em) {
        this.em = em;
    }

    protected CheckpointRepository() {
        this(null);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public AgentCheckpoint persist(AgentCheckpoint entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public AgentCheckpoint merge(AgentCheckpoint entity) {
        AgentCheckpoint merged = em.merge(entity);
        em.flush();
        return merged;
    }

    public Optional<AgentCheckpoint> findById(Long id) {
        em.clear();
        return Optional.ofNullable(em.find(AgentCheckpoint.class, id));
    }

    public List<AgentCheckpoint> findBySessionAndRole(String sessionId, String agentRole) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "  AND c.agentRole = :role " +
                "  AND c.status <> :archived " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("role", agentRole)
                .setParameter("archived", CheckpointStatus.ARCHIVED)
                .setMaxResults(1)
                .getResultList();
    }

    public List<AgentCheckpoint> findActiveBySession(String sessionId) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid " +
                "  AND c.status NOT IN (:terminalStatuses) " +
                "ORDER BY c.id ASC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
                .getResultList();
    }

    public List<AgentCheckpoint> findLatestAwaiting(String sessionId) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid " +
                "  AND c.status IN (:s1, :s2, :s3) " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("s1", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("s2", CheckpointStatus.RESUMING)
                .setParameter("s3", CheckpointStatus.IN_PROGRESS)
                .setMaxResults(1)
                .getResultList();
    }

    public List<AgentCheckpoint> findByStatus(String status) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.status = :status ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("status", CheckpointStatus.valueOf(status))
                .getResultList();
    }

    public List<AgentCheckpoint> findLatestBySession(String sessionId) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setMaxResults(1)
                .getResultList();
    }

    public List<AgentCheckpoint> findLatestNonArchived(String sessionId) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status <> :archived ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("archived", CheckpointStatus.ARCHIVED)
                .setMaxResults(1)
                .getResultList();
    }

    public List<AgentCheckpoint> findLatestBySessionAndStatus(String sessionId, String status) {
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status = :status ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("status", CheckpointStatus.valueOf(status))
                .setMaxResults(1)
                .getResultList();
    }

    public List<AgentCheckpoint> findLatestAwaitingBySegment(String segment) {
        em.clear();
        return em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId LIKE :segment " +
                "  AND c.status IN (:s1, :s2, :s3) " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
                .setParameter("segment", "%" + segment + "%")
                .setParameter("s1", CheckpointStatus.AWAITING_APPROVAL)
                .setParameter("s2", CheckpointStatus.RESUMING)
                .setParameter("s3", CheckpointStatus.IN_PROGRESS)
                .setMaxResults(1)
                .getResultList();
    }

    public boolean hasPendingApprovalForChat(String chatId) {
        if (chatId == null) return false;
        em.clear();
        return !em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.channelPlatformId = :chatId AND c.status = :s",
                AgentCheckpoint.class)
                .setParameter("chatId", chatId)
                .setParameter("s", CheckpointStatus.AWAITING_APPROVAL)
                .getResultList()
                .isEmpty();
    }
}
