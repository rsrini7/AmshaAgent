package com.amsha.persistence.sqlite.jdbi;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingExtensionStateDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(OnboardingExtensionStateDto.class)
public interface OnboardingExtensionStateJdbiDao {

    @SqlUpdate(
            "INSERT INTO onboarding_extension_state (draft_id, extension_id, extension_version, scope, outcome, created_at, updated_at) "
                    + "VALUES (:draftId, :extensionId, :extensionVersion, :scope, :outcome, :createdAt, :updatedAt) "
                    + "ON CONFLICT(draft_id, extension_id) DO UPDATE SET outcome = EXCLUDED.outcome, "
                    + "extension_version = EXCLUDED.extension_version, scope = EXCLUDED.scope, "
                    + "updated_at = EXCLUDED.updated_at")
    int save(@BindMethods OnboardingExtensionStateDto state);

    @SqlQuery("SELECT * FROM onboarding_extension_state WHERE draft_id = :draftId")
    List<OnboardingExtensionStateDto> listByDraft(@Bind("draftId") String draftId);

    @SqlQuery("SELECT * FROM onboarding_extension_state WHERE draft_id = :draftId AND extension_id = :extensionId")
    Optional<OnboardingExtensionStateDto> findByDraftAndExtension(
            @Bind("draftId") String draftId, @Bind("extensionId") String extensionId);

    @SqlUpdate("DELETE FROM onboarding_extension_state WHERE draft_id = :draftId")
    int deleteByDraft(@Bind("draftId") String draftId);
}
