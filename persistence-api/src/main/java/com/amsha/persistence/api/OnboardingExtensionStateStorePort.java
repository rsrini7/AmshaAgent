package com.amsha.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Port for durable optional-step extension outcomes. Three backends (SQLite, PostgreSQL, filesystem)
 * implement one contract.
 */
public interface OnboardingExtensionStateStorePort {

    List<OnboardingExtensionStateDto> listByDraft(String draftId);

    Optional<OnboardingExtensionStateDto> findByDraftAndExtension(String draftId, String extensionId);

    boolean save(OnboardingExtensionStateDto state);

    boolean update(OnboardingExtensionStateDto state, int expectedRevisionUnused);

    int deleteByDraft(String draftId);
}
