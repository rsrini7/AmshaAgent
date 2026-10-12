package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingCommitReceiptStorePort;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.sqlite.jdbi.OnboardingCommitReceiptJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.SQLITE, enableIfMissing = true)
public class SqliteOnboardingCommitReceiptStoreAdapter implements OnboardingCommitReceiptStorePort {

    private final Jdbi jdbi;

    @Inject
    public SqliteOnboardingCommitReceiptStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteOnboardingCommitReceiptStoreAdapter() {
        this(null);
    }

    @Override
    public void upsert(OnboardingCommitReceiptDto receipt) {
        jdbi.useExtension(OnboardingCommitReceiptJdbiDao.class, dao -> dao.upsert(receipt));
    }

    @Override
    public Optional<OnboardingCommitReceiptDto> findByOperationId(String operationId) {
        return jdbi.withExtension(OnboardingCommitReceiptJdbiDao.class, dao -> dao.findByOperationId(operationId));
    }

    @Override
    public List<OnboardingCommitReceiptDto> listByDraftId(String draftId) {
        return jdbi.withExtension(OnboardingCommitReceiptJdbiDao.class, dao -> dao.listByDraftId(draftId));
    }

    @Override
    public int deleteByDraftId(String draftId) {
        return jdbi.withExtension(OnboardingCommitReceiptJdbiDao.class, dao -> dao.deleteByDraftId(draftId));
    }
}
