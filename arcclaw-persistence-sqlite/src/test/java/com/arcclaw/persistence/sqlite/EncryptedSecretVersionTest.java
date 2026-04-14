package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.security.EncryptedSecret;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EncryptedSecretVersionTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanup() {
        em.createQuery("DELETE FROM EncryptedSecret").executeUpdate();
    }

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
    void testEncryptedSecret_versionIncrementsOnCiphertextUpdate() {
        EncryptedSecret.EncryptedSecretId id = new EncryptedSecret.EncryptedSecretId("session-2", "key-2");

        // Step 1: persist in committed transaction
        QuarkusTransaction.requiringNew().run(() -> {
            EncryptedSecret secret = new EncryptedSecret();
            secret.id = id;
            secret.ciphertext = "cipher".getBytes();
            secret.nonce = "nonce".getBytes();
            em.persist(secret);
            em.flush();
            assertEquals(0, secret.getOrmVersion(), "Initial version should be 0");
        });

        // Step 2: update in a new committed transaction
        QuarkusTransaction.requiringNew().run(() -> {
            EncryptedSecret secret = em.find(EncryptedSecret.class, id);
            secret.ciphertext = "new-cipher".getBytes();
        });

        // Step 3: verify version incremented
        QuarkusTransaction.requiringNew().run(() -> {
            em.clear();
            EncryptedSecret secret = em.find(EncryptedSecret.class, id);
            assertEquals(1, secret.getOrmVersion(), "Version should increment on update");
        });
    }

    @Test
    void testEncryptedSecret_optimisticLockPreventsLostUpdate() {
        EncryptedSecret.EncryptedSecretId id = new EncryptedSecret.EncryptedSecretId("session-3", "key-3");

        // 1. Persist in a committed transaction
        QuarkusTransaction.requiringNew().run(() -> {
            EncryptedSecret secret = new EncryptedSecret();
            secret.id = id;
            secret.ciphertext = "initial".getBytes();
            secret.nonce = "nonce".getBytes();
            em.persist(secret);
        });

        // 2. Load two detached copies
        EncryptedSecret copy1 = QuarkusTransaction.requiringNew().call(() -> {
            EncryptedSecret s = em.find(EncryptedSecret.class, id);
            em.detach(s);
            return s;
        });

        EncryptedSecret copy2 = QuarkusTransaction.requiringNew().call(() -> {
            EncryptedSecret s = em.find(EncryptedSecret.class, id);
            em.detach(s);
            return s;
        });

        assertEquals(0, copy1.getOrmVersion());
        assertEquals(0, copy2.getOrmVersion());

        // 3. Update copy1 — version advances
        QuarkusTransaction.requiringNew().run(() -> {
            EncryptedSecret s = em.find(EncryptedSecret.class, id);
            s.ciphertext = "updated-1".getBytes();
        });

        // 4. Attempt to merge copy2 (stale version=0) — expect OLE
        copy2.ciphertext = "updated-2".getBytes();
        assertThrows(Exception.class, () -> {
            QuarkusTransaction.requiringNew().run(() -> {
                em.merge(copy2);
            });
        });
    }
}
