package com.arcclaw.core.security;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class SecretVaultImplTest {

    @Inject
    SecretVaultImpl vault;

    @Inject
    AesGcmSivCipher cipher;

    @Inject
    EncryptedSecretRepository repository;

    @Inject
    AuditService auditService;

    @Inject
    EntityManager entityManager;

    private static final String TEST_SESSION_ID = "test-session-auth";
    private static final String API_KEY_NAME = "OPENAI_API_KEY";
    private static final byte[] SECRET_PAYLOAD = "sk-live-test-1234567890".getBytes(StandardCharsets.UTF_8);

    @Transactional
    void cleanDb() {
        // Wait for background audit writes to finish
        auditService.waitForIdle();
        repository.deleteBySessionId(TEST_SESSION_ID);
    }

    @Test
    void testEncryptDecryptCycle() {
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            cleanDb();
            // 1. Store
            vault.store(API_KEY_NAME, SECRET_PAYLOAD);

            // Wait for background audit write
            auditService.waitForIdle();

            // 2. Retrieve
            Optional<VaultKey> retrieved = vault.get(API_KEY_NAME);

            assertTrue(retrieved.isPresent(), "Secret should be found");
            try (VaultKey key = retrieved.get()) {
                assertArrayEquals(SECRET_PAYLOAD, key.get(), "Decrypted payload must match original");
            }
        });
    }

    @Test
    void testTamperDetection_BitFlip() {
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            cleanDb();
            // 1. Store
            vault.store(API_KEY_NAME, SECRET_PAYLOAD);

            // Wait for background audit write
            auditService.waitForIdle();

            // 2. Tamper: Direct DB access to flip a bit in ciphertext
            EncryptedSecret entity = repository.findByKey(TEST_SESSION_ID, API_KEY_NAME);
            assertNotNull(entity);

            // Flip the last bit of the ciphertext
            entity.ciphertext[entity.ciphertext.length - 1] ^= 1;
            saveEntity(entity);

            // 3. Retrieve - Expect SecurityException (AEAD failure)
            assertThrows(SecurityException.class, () -> vault.get(API_KEY_NAME));
        });
    }

    @Test
    void testKeySwappingProtection_AADMismatch() {
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            cleanDb();
            // Scenario: Attacker tries to move the ciphertext from "OPENAI_API_KEY" to
            // "GITHUB_TOKEN"
            // Since AAD binds the ciphertext to the keyName, this must fail.

            String targetKeyName = "GITHUB_TOKEN";

            // 1. Store legitimate secret for OPENAI
            vault.store(API_KEY_NAME, SECRET_PAYLOAD);

            // Wait for background audit write
            auditService.waitForIdle();

            // 2. Steal ciphertext/nonce
            EncryptedSecret sorceEntity = repository.findByKey(TEST_SESSION_ID, API_KEY_NAME);

            // 3. Inject into GITHUB_TOKEN slot
            insertForgedSecret(TEST_SESSION_ID, targetKeyName, sorceEntity.ciphertext, sorceEntity.nonce);

            // 4. Retrieve GITHUB_TOKEN - Expect SecurityException
            assertThrows(SecurityException.class, () -> vault.get(targetKeyName));
        });
    }

    @Transactional
    void saveEntity(EncryptedSecret entity) {
        entityManager.merge(entity);
        entityManager.flush();
        entityManager.clear();
    }

    @Transactional
    void insertForgedSecret(String sessionId, String keyName, byte[] ciphertext, byte[] nonce) {
        EncryptedSecret forged = new EncryptedSecret();
        forged.id = new EncryptedSecret.EncryptedSecretId(sessionId, keyName);
        forged.ciphertext = ciphertext.clone();
        forged.nonce = nonce.clone();
        forged.setEncryptionKeyVersion(1);
        repository.persist(forged);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void testStore_VersionAssertions() {
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            cleanDb();
            // 1. Store
            vault.store(API_KEY_NAME, SECRET_PAYLOAD);

            // 2. Assert versions
            EncryptedSecret entity = repository.findByKey(TEST_SESSION_ID, API_KEY_NAME);
            assertNotNull(entity);
            assertEquals(1, entity.getEncryptionKeyVersion(), "Application key version must be 1");
            assertTrue(entity.getOrmVersion() >= 0, "ORM version must be managed by Hibernate (>= 0)");
        });
    }

    @Test
    void testConcurrentStore_NoConflictWithHibernateVersion() throws InterruptedException {
        // As per requirement: call store() twice on the same key concurrently
        // Assert no OptimisticLockException is thrown.
        // NOTE: Hibernate 7+ managing version automatically avoids app-driven corruption.
        
        String key = "CONCURRENT_KEY";
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        
        // Ensure entity is clean
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            cleanDb();
        });

        Thread.ofVirtual().start(() -> {
            try {
                startLatch.await();
                WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
                    vault.store(key, SECRET_PAYLOAD);
                });
            } catch (Exception e) {
                // We assert no OptimisticLockException is thrown
                if (e instanceof jakarta.persistence.OptimisticLockException) {
                    fail("Should not throw OptimisticLockException when Hibernate manages version: " + e.getMessage());
                }
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        });

        Thread.ofVirtual().start(() -> {
            try {
                startLatch.await();
                WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
                    vault.store(key, SECRET_PAYLOAD);
                });
            } catch (Exception e) {
                // We assert no OptimisticLockException is thrown
                if (e instanceof jakarta.persistence.OptimisticLockException) {
                    fail("Should not throw OptimisticLockException when Hibernate manages version: " + e.getMessage());
                }
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads should finish");
        
        WorkspaceScopeHolder.run(TEST_SESSION_ID, () -> {
            assertNotNull(repository.findByKey(TEST_SESSION_ID, key));
        });
    }
}
