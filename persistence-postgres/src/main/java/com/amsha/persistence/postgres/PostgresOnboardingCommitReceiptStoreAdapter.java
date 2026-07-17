package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingCommitReceiptStorePort;
import com.amsha.persistence.postgres.jdbi.OnboardingCommitReceiptPostgresDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresOnboardingCommitReceiptStoreAdapter implements OnboardingCommitReceiptStorePort {

    private final Jdbi jdbi;

    @Inject
    public PostgresOnboardingCommitReceiptStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresOnboardingCommitReceiptStoreAdapter() {
        this(null);
    }

    @Override
    public void upsert(OnboardingCommitReceiptDto receipt) {
        jdbi.useExtension(OnboardingCommitReceiptPostgresDao.class, dao -> dao.upsert(receipt));
    }

    @Override
    public Optional<OnboardingCommitReceiptDto> findByOperationId(String operationId) {
        return jdbi.withExtension(OnboardingCommitReceiptPostgresDao.class, dao -> dao.findByOperationId(operationId));
    }

    @Override
    public List<OnboardingCommitReceiptDto> listByDraftId(String draftId) {
        return jdbi.withExtension(OnboardingCommitReceiptPostgresDao.class, dao -> dao.listByDraftId(draftId));
    }

    @Override
    public int deleteByDraftId(String draftId) {
        return jdbi.withExtension(OnboardingCommitReceiptPostgresDao.class, dao -> dao.deleteByDraftId(draftId));
    }
}
