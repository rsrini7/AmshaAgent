package com.arcclaw.persistence.postgres;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.persistence.api.SecretDto;
import com.arcclaw.persistence.api.SecretStorePort;
import com.arcclaw.persistence.common.entity.EncryptedSecretEntity;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * PostgreSQL implementation of SecretStorePort.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresSecretStoreAdapter implements SecretStorePort {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public void save(SecretDto dto) {
        EncryptedSecretEntity entity = new EncryptedSecretEntity();
        entity.setSessionId(dto.sessionId());
        entity.setKeyName(dto.keyName());
        entity.setEncryptionKeyVersion(dto.encryptionKeyVersion());
        entity.setCiphertext(dto.ciphertext());
        entity.setNonce(dto.nonce());
        
        em.merge(entity);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<SecretDto> find(String sessionId, String keyName) {
        em.clear();
        EncryptedSecretEntity.EncryptedSecretId id = new EncryptedSecretEntity.EncryptedSecretId(sessionId, keyName);
        EncryptedSecretEntity entity = em.find(EncryptedSecretEntity.class, id);
        return Optional.ofNullable(entity).map(this::toDto);
    }

    @Override
    @Transactional
    public void delete(String sessionId, String keyName) {
        em.clear();
        EncryptedSecretEntity.EncryptedSecretId id = new EncryptedSecretEntity.EncryptedSecretId(sessionId, keyName);
        EncryptedSecretEntity entity = em.find(EncryptedSecretEntity.class, id);
        if (entity != null) {
            em.remove(entity);
        }
    }

    @Override
    @Transactional
    public void deleteBySessionId(String sessionId) {
        em.createQuery("DELETE FROM EncryptedSecretEntity s WHERE s.id.sessionId = :sessionId")
          .setParameter("sessionId", sessionId)
          .executeUpdate();
    }

    private SecretDto toDto(EncryptedSecretEntity entity) {
        return new SecretDto(
            entity.getSessionId(),
            entity.getKeyName(),
            entity.getEncryptionKeyVersion(),
            entity.getCiphertext(),
            entity.getNonce()
        );
    }
}
