package com.arcclaw.core.security;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.util.MdcKeys;
import org.jboss.logging.MDC;

@ApplicationScoped
public class SecretVaultImpl implements SecretVault {

    @Inject
    Argon2idKeyDerivation keyDerivation;

    @Inject
    VaultKeyCache keyCache;

    @Inject
    AesGcmSivCipher cipher;

    @Inject
    EncryptedSecretRepository repository;

    @Inject
    AuditService auditService;

    private final ReentrantLock systemKeyLock = new ReentrantLock();
    private volatile VaultKey cachedSystemMasterKey;

    @PreDestroy
    void cleanup() {
        systemKeyLock.lock();
        try {
            if (cachedSystemMasterKey != null) {
                cachedSystemMasterKey.close();
                cachedSystemMasterKey = null;
            }
        } finally {
            systemKeyLock.unlock();
        }
    }

    private VaultKey getSystemMasterKey() {
        VaultKey current = cachedSystemMasterKey;
        if (current != null && !current.isDestroyed()) {
            return current;
        }
        systemKeyLock.lock();
        try {
            current = cachedSystemMasterKey;
            if (current == null || current.isDestroyed()) {
                cachedSystemMasterKey = keyDerivation.deriveSessionKey(VaultConstants.SYSTEM_SESSION_ID);
                current = cachedSystemMasterKey;
            }
            return current;
        } finally {
            systemKeyLock.unlock();
        }
    }

    @Override
    public void init(String sessionId) {
        // Validation only - key derivation happens on demand to avoid holding keys in
        // memory needlessly
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID required for vault init");
        }
    }

    @Override
    @Transactional
    public void store(String keyName, byte[] secret) {
        String sessionId = WorkspaceScopeHolder.getRequired();

        // 1. Derive Key
        VaultKey sessionKey = keyCache.getOrDerive(sessionId, keyDerivation);
        try {
            // 2. Encrypt (AAD = keyName)
            AesGcmSivCipher.CipherResult result = cipher.encrypt(secret, sessionKey, keyName);

            // 3. Persist
            EncryptedSecret entity = repository.findByKey(sessionId, keyName);
            if (entity == null) {
                entity = new EncryptedSecret();
                entity.id = new EncryptedSecret.EncryptedSecretId(sessionId, keyName);
            }
            entity.ciphertext = result.ciphertext();
            entity.nonce = result.nonce();
            entity.setEncryptionKeyVersion(1);

            repository.persist(entity);

            // Only record audit for non-system sessions to avoid infinite recursion or
            // missing context
            if (!VaultConstants.SYSTEM_SESSION_ID.equals(sessionId)) {
                String userId = (String) MDC.get(MdcKeys.USER_ID);
                auditService.record(sessionId, userId, AgentSentinels.SYSTEM_ROLE, SecurityEventType.VAULT_STORE, "keyName=" + keyName);
            }

        } catch (Exception e) {
            throw e;
        }
        // sessionKey NOT auto-closed here — lifecycle managed by VaultKeyCache
    }

    @Override
    public Optional<VaultKey> get(String keyName) {
        String sessionId = WorkspaceScopeHolder.getRequired();

        // 1. Fetch
        EncryptedSecret entity = repository.findByKey(sessionId, keyName);
        String userId = (String) MDC.get(MdcKeys.USER_ID);

        if (entity == null) {
            auditService.record(sessionId, userId, AgentSentinels.SYSTEM_ROLE, SecurityEventType.VAULT_RETRIEVE_MISS,
                    "keyName=" + keyName);
            return Optional.empty();
        }

        // 2. Derive Key
        VaultKey sessionKey = keyCache.getOrDerive(sessionId, keyDerivation);
        try {
            // 3. Decrypt
            byte[] plaintext = cipher.decrypt(entity.ciphertext, entity.nonce, sessionKey, keyName);
            auditService.record(sessionId, userId, AgentSentinels.SYSTEM_ROLE, SecurityEventType.VAULT_RETRIEVE, "keyName=" + keyName);
            return Optional.of(new VaultKey(plaintext));
        } catch (SecurityException e) {
            // Log security event?
            throw e;
        }
        // sessionKey NOT auto-closed here
    }


    @Override
    public Optional<VaultKey> retrieveSystemKey(String keyName) {
        String sessionId = VaultConstants.SYSTEM_SESSION_ID;

        EncryptedSecret entity = repository.findByKey(sessionId, keyName);
        if (entity == null) {
            return Optional.empty();
        }

        // SYSTEM keys bypass the WorkspaceScoped cache to avoid
        // ContextNotActiveException during startup/boot.
        // We use a local application-scoped cache for the "SYSTEM" master key
        // to avoid expensive re-derivation (Argon2id) which can cause transaction timeouts.
        VaultKey systemKey = getSystemMasterKey();
        try {
            byte[] plaintext = cipher.decrypt(entity.ciphertext, entity.nonce, systemKey, keyName);
            return Optional.of(new VaultKey(plaintext));
        } catch (SecurityException e) {
            throw e;
        }
    }

    @Override
    public boolean systemKeyExists(String keyName) {
        return repository.findByKey(VaultConstants.SYSTEM_SESSION_ID, keyName) != null;
    }

    @Override
    @Transactional
    public void storeSystemKey(String keyName, byte[] secret) {
        String sessionId = VaultConstants.SYSTEM_SESSION_ID;

        // SYSTEM keys bypass the WorkspaceScoped cache to avoid
        // ContextNotActiveException during startup/boot
        VaultKey systemKey = getSystemMasterKey();
        try {
            AesGcmSivCipher.CipherResult result = cipher.encrypt(secret, systemKey, keyName);

            EncryptedSecret entity = repository.findByKey(sessionId, keyName);
            if (entity == null) {
                entity = new EncryptedSecret();
                entity.id = new EncryptedSecret.EncryptedSecretId(sessionId, keyName);
            }
            entity.ciphertext = result.ciphertext();
            entity.nonce = result.nonce();
            entity.setEncryptionKeyVersion(1);

            repository.persist(entity);
        } catch (Exception e) {
            throw e;
        }
    }

    @Override
    @Transactional
    public void removeSystemKey(String keyName) {
        repository.deleteByKey(VaultConstants.SYSTEM_SESSION_ID, keyName);
    }
}
