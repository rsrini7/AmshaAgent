package com.arcclaw.persistence.sqlite;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.state.ReasoningStepEntity;
import com.arcclaw.persistence.api.ReasoningStepDto;

/**
 * Handles core JPA operations for ReasoningStepEntity objects.
 */
@ApplicationScoped
public class ReasoningStepRepository {

    private final EntityManager em;

    @Inject
    public ReasoningStepRepository(EntityManager em) {
        this.em = em;
    }

    protected ReasoningStepRepository() {
        this(null);
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void persistSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        em.clear();
        em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId = :id")
                .setParameter("id", checkpointId)
                .executeUpdate();

        for (int i = 0; i < steps.size(); i++) {
            ReasoningStepDto dto = steps.get(i);
            ReasoningStepEntity entity = new ReasoningStepEntity();
            entity.setCheckpointId(checkpointId);
            entity.setSequenceNum(i);
            entity.setThoughtJson(dto.thoughtJson());
            entity.setAction(dto.action());
            entity.setActionInputJson(dto.actionInputJson());
            entity.setObservation(dto.observation());
            entity.setInputTokens(dto.inputTokens());
            entity.setOutputTokens(dto.outputTokens());
            entity.setSuccess(dto.success());
            entity.setErrorCode(dto.errorCode());
            entity.setTraceId(dto.traceId());
            entity.setRootCauseClass(dto.rootCauseClass());
            em.persist(entity);
        }
        em.flush();
    }

    public List<ReasoningStepDto> loadSteps(Long checkpointId) {
        return em.createQuery(
                "SELECT r FROM ReasoningStepEntity r WHERE r.checkpointId = :id ORDER BY r.sequenceNum ASC",
                ReasoningStepEntity.class)
                .setParameter("id", checkpointId)
                .getResultList()
                .stream()
                .map(s -> new ReasoningStepDto(s.getThoughtJson(), s.getAction(), s.getActionInputJson(),
                        s.getObservation(), s.getInputTokens(), s.getOutputTokens(), s.getSuccess(), 
                        s.getErrorCode(), s.getTraceId(), s.getRootCauseClass()))
                .collect(Collectors.toList());
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void deleteSteps(Long checkpointId) {
        em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId = :id")
                .setParameter("id", checkpointId)
                .executeUpdate();
    }
}
