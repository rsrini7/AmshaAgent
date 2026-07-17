package com.amsha.persistence.sqlite.jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * SQLite has no native array type, so {@code List<String>} draft columns (completedStepIds,
 * selectedNonSecretIds, pendingApprovalIds) are stored as JSON text and {@code purpose}/{@code state}
 * are stored as enum names. A custom {@link DraftRowMapper} is therefore required instead of
 * {@code @RegisterConstructorMapper}: the constructor mapper cannot deserialize JSON arrays or coerce
 * the enum/Instant columns. {@code DraftRowMapper} is registered for native reflection in
 * {@code SqlitePersistenceReflectionConfig}, so this is an intentional, documented deviation from the
 * usual JDBI record-mapping convention (not a missing registration).
 */
public interface OnboardingDraftJdbiDao {

    ObjectMapper MAPPER = new ObjectMapper();

    @SqlUpdate("INSERT INTO onboarding_draft (draft_id, owner_id, purpose, plan_fingerprint, current_step_id, "
            + "completed_step_ids, selected_non_secret_ids, safe_field_errors, pending_approval_ids, "
            + "state, revision, expires_at, created_at, updated_at) "
            + "VALUES (:draftId, :ownerId, :purpose, :planFingerprint, :currentStepId, :completedStepIds, "
            + ":selectedNonSecretIds, :safeFieldErrors, :pendingApprovalIds, :state, 1, :expiresAt, :createdAt, :updatedAt) "
            + "ON CONFLICT(draft_id) DO NOTHING")
    int save(
            @Bind("draftId") String draftId,
            @Bind("ownerId") String ownerId,
            @Bind("purpose") String purpose,
            @Bind("planFingerprint") String planFingerprint,
            @Bind("currentStepId") String currentStepId,
            @Bind("completedStepIds") String completedStepIds,
            @Bind("selectedNonSecretIds") String selectedNonSecretIds,
            @Bind("safeFieldErrors") String safeFieldErrors,
            @Bind("pendingApprovalIds") String pendingApprovalIds,
            @Bind("state") String state,
            @Bind("expiresAt") Instant expiresAt,
            @Bind("createdAt") Instant createdAt,
            @Bind("updatedAt") Instant updatedAt);

    @SqlUpdate("UPDATE onboarding_draft SET plan_fingerprint = :planFingerprint, current_step_id = :currentStepId, "
            + "completed_step_ids = :completedStepIds, selected_non_secret_ids = :selectedNonSecretIds, "
            + "safe_field_errors = :safeFieldErrors, pending_approval_ids = :pendingApprovalIds, "
            + "state = :state, revision = onboarding_draft.revision + 1, updated_at = :updatedAt "
            + "WHERE draft_id = :draftId AND revision = :expectedRevision")
    int update(
            @Bind("draftId") String draftId,
            @Bind("planFingerprint") String planFingerprint,
            @Bind("currentStepId") String currentStepId,
            @Bind("completedStepIds") String completedStepIds,
            @Bind("selectedNonSecretIds") String selectedNonSecretIds,
            @Bind("safeFieldErrors") String safeFieldErrors,
            @Bind("pendingApprovalIds") String pendingApprovalIds,
            @Bind("state") String state,
            @Bind("updatedAt") Instant updatedAt,
            @Bind("expectedRevision") int expectedRevision);

    @SqlQuery("SELECT * FROM onboarding_draft WHERE draft_id = :draftId")
    @RegisterRowMapper(DraftRowMapper.class)
    Optional<OnboardingDraftDto> findById(@Bind("draftId") String draftId);

    @SqlQuery("SELECT * FROM onboarding_draft WHERE owner_id = :ownerId "
            + "AND state IN ('DRAFT','WAITING_APPROVAL','RESUMABLE','COMMITTING') "
            + "AND (expires_at IS NULL OR expires_at > :now) "
            + "ORDER BY updated_at DESC LIMIT 1")
    @RegisterRowMapper(DraftRowMapper.class)
    Optional<OnboardingDraftDto> findActiveByOwner(@Bind("ownerId") String ownerId, @Bind("now") Instant now);

    @SqlQuery("SELECT * FROM onboarding_draft WHERE owner_id = :ownerId ORDER BY updated_at DESC")
    @RegisterRowMapper(DraftRowMapper.class)
    List<OnboardingDraftDto> listByOwner(@Bind("ownerId") String ownerId);

    @SqlUpdate("DELETE FROM onboarding_draft WHERE draft_id = :draftId")
    int delete(@Bind("draftId") String draftId);

    class DraftRowMapper implements RowMapper<OnboardingDraftDto> {
        private static final TypeReference<List<String>> LIST = new TypeReference<>() {};

        @Override
        public OnboardingDraftDto map(ResultSet rs, StatementContext ctx) throws SQLException {
            return new OnboardingDraftDto(
                    rs.getString("draft_id"),
                    rs.getString("owner_id"),
                    OnboardingDraftPurpose.valueOf(rs.getString("purpose")),
                    rs.getString("plan_fingerprint"),
                    rs.getString("current_step_id"),
                    parseList(rs.getString("completed_step_ids")),
                    parseList(rs.getString("selected_non_secret_ids")),
                    rs.getString("safe_field_errors"),
                    rs.getString("pending_approval_ids"),
                    OnboardingDraftState.valueOf(rs.getString("state")),
                    rs.getInt("revision"),
                    toInstant(rs.getObject("expires_at")),
                    toInstant(rs.getObject("created_at")),
                    toInstant(rs.getObject("updated_at")));
        }

        private static List<String> parseList(String json) {
            if (json == null || json.isBlank()) return List.of();
            try {
                return MAPPER.readValue(json, LIST);
            } catch (Exception _) {
                return List.of();
            }
        }

        private static Instant toInstant(Object o) {
            if (o == null) return null;
            if (o instanceof Instant i) return i;
            if (o instanceof Timestamp t) return t.toInstant();
            return Instant.parse(o.toString());
        }
    }
}
