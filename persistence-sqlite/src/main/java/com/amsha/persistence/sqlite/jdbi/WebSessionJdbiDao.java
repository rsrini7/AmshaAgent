package com.amsha.persistence.sqlite.jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.WebSessionDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(WebSessionDto.class)
public interface WebSessionJdbiDao {

    @SqlUpdate(
            "INSERT INTO web_session (session_id, user_id, token_digest, csrf_digest, idle_expires_at, absolute_expires_at, rotated_at, revoked_at, safe_client_metadata, revision, created_at, updated_at) "
                    + "VALUES (:sessionId, :userId, :tokenDigest, :csrfDigest, :idleExpiresAt, :absoluteExpiresAt, :rotatedAt, :revokedAt, :safeClientMetadata, :revision, :createdAt, :updatedAt) "
                    + "ON CONFLICT(session_id) DO UPDATE SET user_id = excluded.user_id, token_digest = excluded.token_digest, csrf_digest = excluded.csrf_digest, "
                    + "idle_expires_at = excluded.idle_expires_at, absolute_expires_at = excluded.absolute_expires_at, rotated_at = excluded.rotated_at, "
                    + "revoked_at = excluded.revoked_at, safe_client_metadata = excluded.safe_client_metadata, revision = web_session.revision + 1, updated_at = excluded.updated_at "
                    + "WHERE web_session.revision = excluded.revision")
    int save(@BindMethods WebSessionDto session);

    @SqlQuery(
            "SELECT session_id AS \"sessionId\", user_id AS \"userId\", token_digest AS \"tokenDigest\", csrf_digest AS \"csrfDigest\", "
                    + "idle_expires_at AS \"idleExpiresAt\", absolute_expires_at AS \"absoluteExpiresAt\", rotated_at AS \"rotatedAt\", "
                    + "revoked_at AS \"revokedAt\", safe_client_metadata AS \"safeClientMetadata\", revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM web_session WHERE session_id = :sessionId")
    Optional<WebSessionDto> findById(@Bind("sessionId") String sessionId);

    @SqlQuery(
            "SELECT session_id AS \"sessionId\", user_id AS \"userId\", token_digest AS \"tokenDigest\", csrf_digest AS \"csrfDigest\", "
                    + "idle_expires_at AS \"idleExpiresAt\", absolute_expires_at AS \"absoluteExpiresAt\", rotated_at AS \"rotatedAt\", "
                    + "revoked_at AS \"revokedAt\", safe_client_metadata AS \"safeClientMetadata\", revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM web_session WHERE token_digest = :tokenDigest")
    Optional<WebSessionDto> findByTokenDigest(@Bind("tokenDigest") String tokenDigest);

    @SqlQuery(
            "SELECT session_id AS \"sessionId\", user_id AS \"userId\", token_digest AS \"tokenDigest\", csrf_digest AS \"csrfDigest\", "
                    + "idle_expires_at AS \"idleExpiresAt\", absolute_expires_at AS \"absoluteExpiresAt\", rotated_at AS \"rotatedAt\", "
                    + "revoked_at AS \"revokedAt\", safe_client_metadata AS \"safeClientMetadata\", revision, created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM web_session WHERE user_id = :userId ORDER BY created_at ASC")
    List<WebSessionDto> listByUserId(@Bind("userId") String userId);

    @SqlUpdate("DELETE FROM web_session WHERE session_id = :sessionId")
    int deleteById(@Bind("sessionId") String sessionId);

    @SqlUpdate("DELETE FROM web_session WHERE user_id = :userId")
    int deleteByUserId(@Bind("userId") String userId);

    @SqlUpdate("DELETE FROM web_session WHERE idle_expires_at < :now OR absolute_expires_at < :now")
    int deleteExpired(@Bind("now") Instant now);
}
