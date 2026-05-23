package com.arcclaw.persistence.postgres.jdbi;

import java.util.Optional;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(SecretJdbiDao.SecretDtoRow.class)
public interface SecretJdbiDao {

    @SqlUpdate("INSERT INTO encrypted_secrets (session_id, key_name, encryption_key_version, ciphertext, nonce) VALUES (:sessionId, :keyName, :encryptionKeyVersion, :ciphertext, :nonce) ON CONFLICT (session_id, key_name) DO UPDATE SET encryption_key_version = EXCLUDED.encryption_key_version, ciphertext = EXCLUDED.ciphertext, nonce = EXCLUDED.nonce")
    void save(@Bind("sessionId") String sessionId, @Bind("keyName") String keyName, @Bind("encryptionKeyVersion") int encryptionKeyVersion, @Bind("ciphertext") byte[] ciphertext, @Bind("nonce") byte[] nonce);

    @SqlQuery("SELECT session_id AS \"sessionId\", key_name AS \"keyName\", encryption_key_version AS \"encryptionKeyVersion\", ciphertext, nonce FROM encrypted_secrets WHERE session_id = :sessionId AND key_name = :keyName")
    Optional<SecretDtoRow> find(@Bind("sessionId") String sessionId, @Bind("keyName") String keyName);

    @SqlUpdate("DELETE FROM encrypted_secrets WHERE session_id = :sessionId AND key_name = :keyName")
    void delete(@Bind("sessionId") String sessionId, @Bind("keyName") String keyName);

    @SqlUpdate("DELETE FROM encrypted_secrets WHERE session_id = :sessionId")
    void deleteBySessionId(@Bind("sessionId") String sessionId);

    record SecretDtoRow(String sessionId, String keyName, int encryptionKeyVersion, byte[] ciphertext, byte[] nonce) {}
}
