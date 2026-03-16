package com.arcclaw.core.security;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EncryptedSecretVersionTest {

    @Inject
    EntityManager em;

    @Test
    @Transactional
    void testEncryptedSecret_versionFieldExistsAndStartsAtZero() {
        EncryptedSecret.EncryptedSecretId id = new EncryptedSecret.EncryptedSecretId("session-1", "key-1");
        EncryptedSecret secret = new EncryptedSecret();
        secret.id = id;
        secret.ciphertext = "cipher".getBytes();
        secret.nonce = "nonce".getBytes();

        em.persist(secret);
        em.flush();

        assertEquals(0, secret.getOrmVersion(), "Version should be 0 on first persist");
    }

    @Test
    @Transactional
    void testEncryptedSecret_versionIncrementsOnCiphertextUpdate() {
        EncryptedSecret.EncryptedSecretId id = new EncryptedSecret.EncryptedSecretId("session-2", "key-2");
        EncryptedSecret secret = new EncryptedSecret();
        secret.id = id;
        secret.ciphertext = "cipher".getBytes();
        secret.nonce = "nonce".getBytes();

        em.persist(secret);
        em.flush();
        int v0 = secret.getOrmVersion();

        secret.ciphertext = "new-cipher".getBytes();
        em.merge(secret);
        em.flush();

        assertEquals(v0 + 1, secret.getOrmVersion(), "Version should increment on update");
    }

    @Test
    void testEncryptedSecret_optimisticLockPreventsLostUpdate() {
        EncryptedSecret.EncryptedSecretId id = new EncryptedSecret.EncryptedSecretId("session-3", "key-3");
        
        // 1. Persist
        persistInNewTransaction(id);

        // 2. Load two copies
        EncryptedSecret copy1 = em.find(EncryptedSecret.class, id);
        EncryptedSecret copy2 = em.find(EncryptedSecret.class, id);
        assertEquals(0, copy1.getOrmVersion());
        assertEquals(0, copy2.getOrmVersion());

        // 3. Update copy1
        updateCiphertextInNewTransaction(id, "updated-1");

        // 4. Attempt to update copy2
        copy2.ciphertext = "updated-2".getBytes();
        
        assertThrows(Exception.class, () -> {
            tryMergeInNewTransaction(copy2);
        });
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void persistInNewTransaction(EncryptedSecret.EncryptedSecretId id) {
        EncryptedSecret secret = new EncryptedSecret();
        secret.id = id;
        secret.ciphertext = "initial".getBytes();
        secret.nonce = "nonce".getBytes();
        em.persist(secret);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void updateCiphertextInNewTransaction(EncryptedSecret.EncryptedSecretId id, String newCipher) {
        EncryptedSecret secret = em.find(EncryptedSecret.class, id);
        secret.ciphertext = newCipher.getBytes();
        em.merge(secret);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void tryMergeInNewTransaction(EncryptedSecret secret) {
        em.merge(secret);
    }
}
