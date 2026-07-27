package com.amsha.persistence.postgres.jdbi;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingCommitReceiptDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(OnboardingCommitReceiptDto.class)
public interface OnboardingCommitReceiptPostgresDao {

    @SqlUpdate(
            "INSERT INTO onboarding_commit_receipt (id,draft_id,extension_id,operation_id,receipt,state,created_at,updated_at) "
                    + "VALUES (:id,:draftId,:extensionId,:operationId,:receipt,:state,:createdAt,:updatedAt) "
                    + "ON CONFLICT(operation_id) DO UPDATE SET receipt=excluded.receipt,state=excluded.state,updated_at=excluded.updated_at")
    void upsert(@BindMethods OnboardingCommitReceiptDto receipt);

    @SqlQuery("SELECT * FROM onboarding_commit_receipt WHERE operation_id = :operationId")
    Optional<OnboardingCommitReceiptDto> findByOperationId(@Bind("operationId") String operationId);

    @SqlQuery("SELECT * FROM onboarding_commit_receipt WHERE draft_id = :draftId ORDER BY created_at ASC")
    List<OnboardingCommitReceiptDto> listByDraftId(@Bind("draftId") String draftId);

    @SqlUpdate("DELETE FROM onboarding_commit_receipt WHERE draft_id = :draftId")
    int deleteByDraftId(@Bind("draftId") String draftId);
}
