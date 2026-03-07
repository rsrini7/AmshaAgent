package com.arcclaw.core.security;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.util.MdcKeys;
import org.jboss.logging.MDC;

import jakarta.transaction.Transactional;

import java.util.Optional;

@Dependent
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
            entity.version = 1;

            repository.persist(entity);
            
            String userId = (String) MDC.get(MdcKeys.USER_ID);
            auditService.record(sessionId, userId, "system", SecurityEventType.VAULT_STORE, "keyName=" + keyName);
            
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
            auditService.record(sessionId, userId, "system", SecurityEventType.VAULT_RETRIEVE_MISS, "keyName=" + keyName);
            return Optional.empty();
        }

        // 2. Derive Key
        VaultKey sessionKey = keyCache.getOrDerive(sessionId, keyDerivation);
        try {
            // 3. Decrypt
            byte[] plaintext = cipher.decrypt(entity.ciphertext, entity.nonce, sessionKey, keyName);
            auditService.record(sessionId, userId, "system", SecurityEventType.VAULT_RETRIEVE, "keyName=" + keyName);
            return Optional.of(new VaultKey(plaintext));
        } catch (SecurityException e) {
            // Log security event?
            throw e;
        }
        // sessionKey NOT auto-closed here
    }

    @Override
    @Transactional
    public void rotateSecrets(String sessionId, String oldPepper, String newPepper) {
        // Not implemented in Phase 2 iteration 1
        throw new UnsupportedOperationException("Key rotation not yet implemented in Phase 2");
    }
}
