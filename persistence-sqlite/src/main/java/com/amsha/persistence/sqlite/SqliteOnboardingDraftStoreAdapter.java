package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftStorePort;
import com.amsha.persistence.sqlite.jdbi.OnboardingDraftJdbiDao;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteOnboardingDraftStoreAdapter implements OnboardingDraftStorePort {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Jdbi jdbi;

    @Inject
    public SqliteOnboardingDraftStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteOnboardingDraftStoreAdapter() {
        this(null);
    }

    private String toJson(List<String> list) {
        try {
            return mapper.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception _) {
            return "[]";
        }
    }

    @Override
    public Optional<OnboardingDraftDto> findById(String draftId) {
        return jdbi.withExtension(OnboardingDraftJdbiDao.class, dao -> dao.findById(draftId));
    }

    @Override
    public Optional<OnboardingDraftDto> findActiveByOwner(String ownerId, Instant now) {
        return jdbi.withExtension(OnboardingDraftJdbiDao.class, dao -> dao.findActiveByOwner(ownerId, now));
    }

    @Override
    public List<OnboardingDraftDto> listByOwner(String ownerId) {
        return jdbi.withExtension(OnboardingDraftJdbiDao.class, dao -> dao.listByOwner(ownerId));
    }

    @Override
    public boolean save(OnboardingDraftDto draft) {
        return jdbi.withExtension(
                        OnboardingDraftJdbiDao.class,
                        dao -> dao.save(
                                draft.draftId(),
                                draft.ownerId(),
                                draft.purpose().name(),
                                draft.planFingerprint(),
                                draft.currentStepId(),
                                toJson(draft.completedStepIds()),
                                toJson(draft.selectedNonSecretIds()),
                                draft.safeFieldErrors(),
                                draft.pendingApprovalIds(),
                                draft.state().name(),
                                draft.expiresAt(),
                                draft.createdAt(),
                                draft.updatedAt()))
                > 0;
    }

    @Override
    public boolean update(OnboardingDraftDto draft, int expectedRevision) {
        return jdbi.withExtension(
                        OnboardingDraftJdbiDao.class,
                        dao -> dao.update(
                                draft.draftId(),
                                draft.planFingerprint(),
                                draft.currentStepId(),
                                toJson(draft.completedStepIds()),
                                toJson(draft.selectedNonSecretIds()),
                                draft.safeFieldErrors(),
                                draft.pendingApprovalIds(),
                                draft.state().name(),
                                draft.updatedAt(),
                                expectedRevision))
                > 0;
    }

    @Override
    public int delete(String draftId) {
        return jdbi.withExtension(OnboardingDraftJdbiDao.class, dao -> dao.delete(draftId));
    }
}
