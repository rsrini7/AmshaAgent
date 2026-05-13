package com.arcclaw.core.security;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.domain.model.EncryptedSecret;
import com.arcclaw.persistence.api.SecretDto;
import com.arcclaw.persistence.api.SecretStorePort;
import io.quarkus.arc.Unremovable;

/**
 * Repository for {@link EncryptedSecret} refactored to delegate to {@link SecretStorePort}.
 * §1.2: Zero EntityManager imports, zero JPQL.
 */
@ApplicationScoped
@Unremovable
public class EncryptedSecretRepository {

    @Inject
    SecretStorePort secretStore;

    @Transactional
    public void save(EncryptedSecret secret) {
        secretStore.save(toDto(secret));
    }

    @Transactional
    public void persist(EncryptedSecret secret) {
        save(secret);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<EncryptedSecret> find(String sessionId, String keyName) {
        return secretStore.find(sessionId, keyName).map(this::toEntity);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<EncryptedSecret> findByKey(String sessionId, String keyName) {
        return find(sessionId, keyName);
    }

    @Transactional
    public void delete(String sessionId, String keyName) {
        secretStore.delete(sessionId, keyName);
    }

    @Transactional
    public void deleteByKey(String sessionId, String keyName) {
        delete(sessionId, keyName);
    }

    @Transactional
    public void deleteBySessionId(String sessionId) {
        secretStore.deleteBySessionId(sessionId);
    }

    private EncryptedSecret toEntity(SecretDto dto) {
        if (dto == null) return null;
        EncryptedSecretEntity entity = new EncryptedSecretEntity();
        entity.setSessionId(dto.sessionId());
        entity.setKeyName(dto.keyName());
        entity.setEncryptionKeyVersion(dto.encryptionKeyVersion());
        entity.setCiphertext(dto.ciphertext());
        entity.setNonce(dto.nonce());
        return entity;
    }

    private SecretDto toDto(EncryptedSecret entity) {
        if (entity == null) return null;
        return new SecretDto(
            entity.getSessionId(),
            entity.getKeyName(),
            entity.getEncryptionKeyVersion(),
            entity.getCiphertext(),
            entity.getNonce()
        );
    }
}
