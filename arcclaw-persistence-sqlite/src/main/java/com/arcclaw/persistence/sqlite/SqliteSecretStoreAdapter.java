package com.arcclaw.persistence.sqlite;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.security.EncryptedSecret;
import com.arcclaw.persistence.api.SecretDto;
import com.arcclaw.persistence.api.SecretStorePort;
import io.quarkus.arc.Unremovable;

/**
 * SQLite implementation of SecretStorePort.
 * §1.2: All JPA logic moved from core to here.
 */
@ApplicationScoped
@Unremovable
public class SqliteSecretStoreAdapter implements SecretStorePort {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public void save(SecretDto dto) {
        EncryptedSecret entity = new EncryptedSecret();
        entity.id = new EncryptedSecret.EncryptedSecretId(dto.sessionId(), dto.keyName());
        entity.setEncryptionKeyVersion(dto.encryptionKeyVersion());
        entity.ciphertext = dto.ciphertext();
        entity.nonce = dto.nonce();
        
        em.merge(entity);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<SecretDto> find(String sessionId, String keyName) {
        em.clear();
        EncryptedSecret.EncryptedSecretId id = new EncryptedSecret.EncryptedSecretId(sessionId, keyName);
        EncryptedSecret entity = em.find(EncryptedSecret.class, id);
        return Optional.ofNullable(entity).map(this::toDto);
    }

    @Override
    @Transactional
    public void delete(String sessionId, String keyName) {
        em.clear();
        EncryptedSecret.EncryptedSecretId id = new EncryptedSecret.EncryptedSecretId(sessionId, keyName);
        EncryptedSecret entity = em.find(EncryptedSecret.class, id);
        if (entity != null) {
            em.remove(entity);
        }
    }

    @Override
    @Transactional
    public void deleteBySessionId(String sessionId) {
        em.createQuery("DELETE FROM EncryptedSecret s WHERE s.id.sessionId = :sessionId")
          .setParameter("sessionId", sessionId)
          .executeUpdate();
    }

    private SecretDto toDto(EncryptedSecret entity) {
        return new SecretDto(
            entity.id.sessionId,
            entity.id.keyName,
            entity.getEncryptionKeyVersion(),
            entity.ciphertext,
            entity.nonce
        );
    }
}
