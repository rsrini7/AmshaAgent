package com.arcclaw.app.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AesGcmSivCipher;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.SecretVaultImpl;
import com.arcclaw.core.security.VaultKey;
import com.arcclaw.persistence.api.SecretDto;
import com.arcclaw.persistence.api.SecretStorePort;
import com.arcclaw.persistence.common.entity.AuditEventEntity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class SecretVaultImplTest {

    @Inject
    SecretVaultImpl vault;

    @Inject
    AesGcmSivCipher cipher;

    @Inject
    SecretStorePort secretStore;

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
        secretStore.deleteBySessionId(TEST_SESSION_ID);
        secretStore.delete("SYSTEM", "test-system-key");
    }

    @Test
    void testEncryptDecryptCycle() {
        WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
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
        WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
            cleanDb();
            // 1. Store
            vault.store(API_KEY_NAME, SECRET_PAYLOAD);

            // Wait for background audit write
            auditService.waitForIdle();

            // 2. Tamper: Direct DB access to flip a bit in ciphertext
            SecretDto dto = secretStore.find(TEST_SESSION_ID, API_KEY_NAME).orElse(null);
            assertNotNull(dto);

            // Flip the last bit of the ciphertext
            byte[] ct = dto.ciphertext();
            ct[ct.length - 1] ^= 1;
            secretStore.save(new SecretDto(
                dto.sessionId(), dto.keyName(), dto.encryptionKeyVersion(), ct, dto.nonce()
            ));

            // 3. Retrieve - Expect SecurityException (AEAD failure)
            assertThrows(SecurityException.class, () -> vault.get(API_KEY_NAME));
        });
    }

    @Test
    void testKeySwappingProtection_AADMismatch() {
        WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
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
            SecretDto sourceDto = secretStore.find(TEST_SESSION_ID, API_KEY_NAME).orElse(null);

            // 3. Inject into GITHUB_TOKEN slot
            insertForgedSecret(TEST_SESSION_ID, targetKeyName, sourceDto.ciphertext(), sourceDto.nonce());

            // 4. Retrieve GITHUB_TOKEN - Expect SecurityException
            assertThrows(SecurityException.class, () -> vault.get(targetKeyName));
        });
    }

    @Transactional
    void insertForgedSecret(String sessionId, String keyName, byte[] ciphertext, byte[] nonce) {
        secretStore.save(new SecretDto(sessionId, keyName, 1, ciphertext.clone(), nonce.clone()));
    }

    @Test
    void testStore_VersionAssertions() {
        WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
            cleanDb();
            // 1. Store
            vault.store(API_KEY_NAME, SECRET_PAYLOAD);

            // 2. Assert versions
            SecretDto dto = secretStore.find(TEST_SESSION_ID, API_KEY_NAME).orElse(null);
            assertNotNull(dto);
            assertEquals(1, dto.encryptionKeyVersion(), "Application key version must be 1");
        });
    }

    @Test
    void testConcurrentStore_LastWriteWins() throws InterruptedException {
        // Since @Version was removed to support 'last write wins' and avoid noisy
        // OptimisticLockExceptions, this test confirms no exception is thrown
        // even during race conditions.
        
        String key = "CONCURRENT_KEY";
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        
        // Ensure entity is clean
        WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
            cleanDb();
        });

        Runnable r = () -> {
            try {
                startLatch.await();
                WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
                    vault.store(key, SECRET_PAYLOAD);
                });
            } catch (Exception e) {
                e.printStackTrace();
                fail("No exception should be thrown during concurrent store: " + e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        };

        Thread.ofVirtual().start(r);
        Thread.ofVirtual().start(r);

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads should finish");
        
        WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
            assertNotNull(secretStore.find(TEST_SESSION_ID, key).orElse(null));
        });
    }


    @Test
    void testSystemKeyLifeCycle() {
        cleanDb();
        String sysKey = "test-system-key";
        byte[] payload = "test-payload".getBytes(StandardCharsets.UTF_8);

        // 1. Initial State
        assertFalse(vault.systemKeyExists(sysKey), "System key should not exist initially");

        // 2. Store
        vault.storeSystemKey(sysKey, payload);

        // 3. Exists
        assertTrue(vault.systemKeyExists(sysKey), "System key should exist after storage");

        // 4. Retrieve
        Optional<VaultKey> retrieved = vault.retrieveSystemKey(sysKey);
        assertTrue(retrieved.isPresent(), "System key should be retrievable");
        try (VaultKey key = retrieved.get()) {
            assertArrayEquals(payload, key.get(), "System key payload must match");
        }
    }
    @Test
    void testVaultWriteDuringToolExecution() {
        // Enclosing transaction simulates tool execution
        // We Use manual transaction to avoid REQUIRED -> REQUIRES_NEW deadlock on SQLite
        deleteSession(TEST_SESSION_ID);
        
        WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
            vault.store(API_KEY_NAME, SECRET_PAYLOAD);
        });
        
        WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
            assertNotNull(secretStore.find(TEST_SESSION_ID, API_KEY_NAME).orElse(null));
        });
    }

    @Test
    void testConcurrentVaultWrites() throws InterruptedException {
        String key = "CONCURRENT_BENCHMARK";
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
            cleanDb();
        });

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
                        vault.store(key, SECRET_PAYLOAD);
                    });
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(15, TimeUnit.SECONDS), "All threads should finish without deadlock");
        
        WorkspaceContext.runWithFullAutonomy(TEST_SESSION_ID, "test-user", "test-chat", "test-role", () -> {
            assertNotNull(secretStore.find(TEST_SESSION_ID, key).orElse(null));
        });
    }
    @Test
    void testVaultAuditOnVirtualThread() throws Exception {
        String testUserId = "test-user-123";
        String testSessionId = "test-session-456";

        cleanDb();
        
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                WorkspaceContext.run(
                    testSessionId,
                    testUserId,
                    "test-chat",
                    AutonomyLevel.FULL,
                    "test-role",
                    () -> {
                        vault.store("test-key", SECRET_PAYLOAD);
                        return null;
                    }
                );
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, Executors.newVirtualThreadPerTaskExecutor());

        future.get();
        auditService.waitForIdle();

        // Verify audit record has correct user ID
        WorkspaceContext.runWithFullAutonomy(testSessionId, testUserId, "test-chat", "test-role", () -> {
            List<AuditEventEntity> events = entityManager.createQuery("SELECT a FROM AuditEvent a WHERE a.sessionId = :sid AND a.eventType = :type", AuditEventEntity.class)
                    .setParameter("sid", testSessionId)
                    .setParameter("type", SecurityEventType.VAULT_STORE)
                    .getResultList();
            
            assertFalse(events.isEmpty(), "Audit event should be recorded");
            assertEquals(testUserId, events.get(0).getUserId());
        });

        // Cleanup on test thread with transaction
        deleteSession(testSessionId);
    }

    @Transactional
    void deleteSession(String sid) {
        secretStore.deleteBySessionId(sid);
    }
}
