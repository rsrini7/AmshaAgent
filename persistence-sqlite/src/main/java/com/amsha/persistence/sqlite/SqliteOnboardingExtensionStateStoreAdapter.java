package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingExtensionStateDto;
import com.amsha.persistence.api.OnboardingExtensionStateStorePort;
import com.amsha.persistence.sqlite.jdbi.OnboardingExtensionStateJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteOnboardingExtensionStateStoreAdapter implements OnboardingExtensionStateStorePort {

    private final Jdbi jdbi;

    @Inject
    public SqliteOnboardingExtensionStateStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteOnboardingExtensionStateStoreAdapter() {
        this(null);
    }

    @Override
    public List<OnboardingExtensionStateDto> listByDraft(String draftId) {
        return jdbi.withExtension(OnboardingExtensionStateJdbiDao.class, dao -> dao.listByDraft(draftId));
    }

    @Override
    public Optional<OnboardingExtensionStateDto> findByDraftAndExtension(String draftId, String extensionId) {
        return jdbi.withExtension(
                OnboardingExtensionStateJdbiDao.class, dao -> dao.findByDraftAndExtension(draftId, extensionId));
    }

    @Override
    public boolean save(OnboardingExtensionStateDto state) {
        return jdbi.withExtension(OnboardingExtensionStateJdbiDao.class, dao -> dao.save(state)) > 0;
    }

    @Override
    public boolean update(OnboardingExtensionStateDto state, int expectedRevisionUnused) {
        return jdbi.withExtension(OnboardingExtensionStateJdbiDao.class, dao -> dao.save(state)) > 0;
    }

    @Override
    public int deleteByDraft(String draftId) {
        return jdbi.withExtension(OnboardingExtensionStateJdbiDao.class, dao -> dao.deleteByDraft(draftId));
    }
}
