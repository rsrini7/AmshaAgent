package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingExtensionStateDto;
import com.amsha.persistence.api.OnboardingExtensionStateStorePort;
import com.amsha.persistence.postgres.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@QuarkusTestResource(PostgresTestResource.class)
@SuppressWarnings("java:S1192")
class PostgresOnboardingExtensionStateStoreAdapterTest {

    @Inject
    OnboardingExtensionStateStorePort store;

    @Inject
    Jdbi jdbi;

    @BeforeEach
    void cleanExtensionState() {
        jdbi.useHandle(h -> h.execute("DELETE FROM onboarding_extension_state"));
    }

    private static OnboardingExtensionStateDto state(String draftId, String extId, String outcome) {
        Instant now = Instant.now();
        return new OnboardingExtensionStateDto(draftId, extId, "v1", "WORKSPACE", outcome, now, now);
    }

    @Test
    void saveFindListAndDeleteByDraft() {
        store.save(state("d-1", "ext-a", OnboardingExtensionStateDto.OUTCOME_CONFIGURED));
        store.save(state("d-1", "ext-b", OnboardingExtensionStateDto.OUTCOME_SKIPPED));
        store.save(state("d-2", "ext-a", OnboardingExtensionStateDto.OUTCOME_CONFIGURED));

        assertEquals(2, store.listByDraft("d-1").size());
        assertEquals(1, store.listByDraft("d-2").size());

        Optional<OnboardingExtensionStateDto> found = store.findByDraftAndExtension("d-1", "ext-a");
        assertTrue(found.isPresent());
        assertEquals(OnboardingExtensionStateDto.OUTCOME_CONFIGURED, found.get().outcome());

        store.update(state("d-1", "ext-a", OnboardingExtensionStateDto.OUTCOME_SKIPPED), 0);
        assertEquals(
                OnboardingExtensionStateDto.OUTCOME_SKIPPED,
                store.findByDraftAndExtension("d-1", "ext-a").orElseThrow().outcome());
        assertEquals(2, store.listByDraft("d-1").size());
    }

    @Test
    void deleteByDraft() {
        store.save(state("d-1", "ext-a", OnboardingExtensionStateDto.OUTCOME_CONFIGURED));
        store.save(state("d-1", "ext-b", OnboardingExtensionStateDto.OUTCOME_SKIPPED));
        assertEquals(2, store.deleteByDraft("d-1"));
        assertTrue(store.listByDraft("d-1").isEmpty());
    }
}
