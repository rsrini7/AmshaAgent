package com.amsha.persistence.postgres.jdbi;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.ApiCredentialDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(ApiCredentialDto.class)
public interface ApiCredentialJdbiDao {

    @SqlUpdate(
            "INSERT INTO api_credential (credential_id, user_id, key_digest, role_set, revision, expires_at, revoked_at, created_at, updated_at) "
                    + "VALUES (:credentialId, :userId, :keyDigest, :roleSet, 0, :expiresAt, :revokedAt, :createdAt, :updatedAt) "
                    + "ON CONFLICT(credential_id) DO UPDATE SET user_id = excluded.user_id, key_digest = excluded.key_digest, role_set = excluded.role_set, "
                    + "revision = api_credential.revision + 1, expires_at = excluded.expires_at, revoked_at = excluded.revoked_at, updated_at = excluded.updated_at "
                    + "WHERE api_credential.revision = excluded.revision")
    int save(@BindMethods ApiCredentialDto credential);

    @SqlQuery(
            "SELECT credential_id AS \"credentialId\", user_id AS \"userId\", key_digest AS \"keyDigest\", role_set AS \"roleSet\", revision, "
                    + "expires_at AS \"expiresAt\", revoked_at AS \"revokedAt\", created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM api_credential WHERE credential_id = :credentialId")
    Optional<ApiCredentialDto> findById(@Bind("credentialId") String credentialId);

    @SqlQuery(
            "SELECT credential_id AS \"credentialId\", user_id AS \"userId\", key_digest AS \"keyDigest\", role_set AS \"roleSet\", revision, "
                    + "expires_at AS \"expiresAt\", revoked_at AS \"revokedAt\", created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM api_credential WHERE key_digest = :keyDigest")
    Optional<ApiCredentialDto> findByKeyDigest(@Bind("keyDigest") String keyDigest);

    @SqlQuery(
            "SELECT credential_id AS \"credentialId\", user_id AS \"userId\", key_digest AS \"keyDigest\", role_set AS \"roleSet\", revision, "
                    + "expires_at AS \"expiresAt\", revoked_at AS \"revokedAt\", created_at AS \"createdAt\", updated_at AS \"updatedAt\" "
                    + "FROM api_credential WHERE user_id = :userId ORDER BY created_at ASC")
    List<ApiCredentialDto> listByUserId(@Bind("userId") String userId);

    @SqlUpdate("DELETE FROM api_credential WHERE credential_id = :credentialId")
    int deleteById(@Bind("credentialId") String credentialId);
}
