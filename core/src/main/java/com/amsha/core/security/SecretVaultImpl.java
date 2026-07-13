package com.amsha.core.security;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.observability.AgentOTelConstants;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.SecretDto;
import com.amsha.persistence.api.SecretStorePort;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

@ApplicationScoped
public class SecretVaultImpl implements SecretVault {

    private static final String AUDIT_KEY_NAME_PREFIX = "keyName=";

    @Inject
    Argon2idKeyDerivation keyDerivation;

    @Inject
    VaultKeyCache keyCache;

    @Inject
    AesGcmSivCipher cipher;

    @Inject
    SecretStorePort secretStore;

    @Inject
    AuditService auditService;

    @Inject
    AgentOtelContext otelContext;

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
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void store(String keyName, byte[] secret) {
        String sessionId = WorkspaceContext.SESSION_ID.isBound()
                ? WorkspaceContext.SESSION_ID.get()
                : VaultConstants.SYSTEM_SESSION_ID;
        store(keyName, secret, WorkspaceContext.getUserIdOrUnknown(), sessionId);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void store(String keyName, byte[] secret, String userId) {
        store(keyName, secret, userId, userVaultScope(userId));
    }

    private void store(String keyName, byte[] secret, String userId, String sessionId) {
        Span span = otelContext.childSpan(sessionId, AgentOTelConstants.SPAN_VAULT_STORE);
        // 1. Derive Key
        VaultKey sessionKey = keyCache.getOrDerive(sessionId, keyDerivation);
        try {
            if (span != null) span.setAttribute(AgentOTelConstants.ATTR_VAULT_KEY_NAME, keyName);
            // 2. Encrypt (AAD = keyName)
            AesGcmSivCipher.CipherResult result = cipher.encrypt(secret, sessionKey, keyName);

            // 3. Persist
            secretStore.save(new SecretDto(sessionId, keyName, 1, result.ciphertext(), result.nonce()));

            // Only record audit for non-system sessions to avoid infinite recursion or
            // missing context
            if (!VaultConstants.SYSTEM_SESSION_ID.equals(sessionId)) {
                auditService.record(
                        sessionId,
                        userId,
                        AgentSentinels.SYSTEM_ROLE,
                        SecurityEventType.VAULT_STORE,
                        AUDIT_KEY_NAME_PREFIX + keyName);
                auditService.record(
                        sessionId,
                        userId,
                        AgentSentinels.SYSTEM_ROLE,
                        SecurityEventType.VAULT_ACCESS,
                        AUDIT_KEY_NAME_PREFIX + keyName + " op=STORE");
            }

            if (span != null) span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            if (span != null) {
                span.setStatus(StatusCode.ERROR, e.getMessage());
                span.recordException(e);
            }
            throw e;
        } finally {
            if (span != null) span.end();
        }
        // sessionKey NOT auto-closed here - lifecycle managed by VaultKeyCache
    }

    @Override
    public Optional<VaultKey> get(String keyName) {
        String sessionId = WorkspaceContext.SESSION_ID.isBound()
                ? WorkspaceContext.SESSION_ID.get()
                : VaultConstants.SYSTEM_SESSION_ID;
        return get(keyName, WorkspaceContext.getUserIdOrUnknown(), sessionId);
    }

    @Override
    public Optional<VaultKey> get(String keyName, String userId) {
        return get(keyName, userId, userVaultScope(userId));
    }

    @SuppressWarnings("java:S2093") // VaultKey lifecycle managed by VaultKeyCache, not local scope
    private Optional<VaultKey> get(String keyName, String userId, String sessionId) {
        Span span = otelContext.childSpan(sessionId, AgentOTelConstants.SPAN_VAULT_RETRIEVE);
        try {
            if (span != null) span.setAttribute(AgentOTelConstants.ATTR_VAULT_KEY_NAME, keyName);
            // 1. Fetch
            SecretDto dto = secretStore.find(sessionId, keyName).orElse(null);

            if (dto == null) {
                if (!VaultConstants.SYSTEM_SESSION_ID.equals(sessionId)) {
                    auditService.record(
                            sessionId,
                            userId,
                            AgentSentinels.SYSTEM_ROLE,
                            SecurityEventType.VAULT_RETRIEVE_MISS,
                            AUDIT_KEY_NAME_PREFIX + keyName);
                    auditService.record(
                            sessionId,
                            userId,
                            AgentSentinels.SYSTEM_ROLE,
                            SecurityEventType.VAULT_ACCESS,
                            AUDIT_KEY_NAME_PREFIX + keyName + " op=RETRIEVE_MISS");
                }
                if (span != null) {
                    span.setAttribute(AgentOTelConstants.ATTR_VAULT_FOUND, false);
                    span.setStatus(StatusCode.OK);
                }
                return Optional.empty();
            }

            // 2. Derive Key
            VaultKey sessionKey = keyCache.getOrDerive(sessionId, keyDerivation);
            // 3. Decrypt
            byte[] plaintext = cipher.decrypt(dto.ciphertext(), dto.nonce(), sessionKey, keyName);
            if (!VaultConstants.SYSTEM_SESSION_ID.equals(sessionId)) {
                auditService.record(
                        sessionId,
                        userId,
                        AgentSentinels.SYSTEM_ROLE,
                        SecurityEventType.VAULT_RETRIEVE,
                        AUDIT_KEY_NAME_PREFIX + keyName);
                auditService.record(
                        sessionId,
                        userId,
                        AgentSentinels.SYSTEM_ROLE,
                        SecurityEventType.VAULT_ACCESS,
                        AUDIT_KEY_NAME_PREFIX + keyName + " op=RETRIEVE");
            }
            if (span != null) {
                span.setAttribute("vault.found", true);
                span.setStatus(StatusCode.OK);
            }
            return Optional.of(new VaultKey(plaintext));
        } catch (SecurityException e) {
            if (span != null) {
                span.setStatus(StatusCode.ERROR, e.getMessage());
                span.recordException(e);
            }
            throw e;
        } finally {
            if (span != null) span.end();
        }
        // sessionKey NOT auto-closed here
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void remove(String keyName, String userId) {
        String sessionId = userVaultScope(userId);

        secretStore.delete(sessionId, keyName);
        if (!VaultConstants.SYSTEM_SESSION_ID.equals(sessionId)) {
            auditService.record(
                    sessionId,
                    userId,
                    AgentSentinels.SYSTEM_ROLE,
                    SecurityEventType.VAULT_ACCESS,
                    AUDIT_KEY_NAME_PREFIX + keyName + " op=DELETE");
        }
    }

    private static String userVaultScope(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required for a user-scoped vault operation");
        }
        return VaultConstants.USER_VAULT_SCOPE_PREFIX + userId;
    }

    @Override
    public Optional<VaultKey> retrieveSystemKey(String keyName) {
        String sessionId = VaultConstants.SYSTEM_SESSION_ID;

        SecretDto dto = secretStore.find(sessionId, keyName).orElse(null);
        if (dto == null) {
            return Optional.empty();
        }

        // SYSTEM keys bypass the WorkspaceScoped cache to avoid
        // ContextNotActiveException during startup/boot.
        // We use a local application-scoped cache for the "SYSTEM" master key
        // to avoid expensive re-derivation (Argon2id) which can cause transaction timeouts.
        VaultKey systemKey = getSystemMasterKey();
        try {
            byte[] plaintext = cipher.decrypt(dto.ciphertext(), dto.nonce(), systemKey, keyName);
            return Optional.of(new VaultKey(plaintext));
        } catch (SecurityException e) {
            throw e;
        }
    }

    @Override
    public boolean systemKeyExists(String keyName) {
        return secretStore.find(VaultConstants.SYSTEM_SESSION_ID, keyName).isPresent();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void storeSystemKey(String keyName, byte[] secret) {
        String sessionId = VaultConstants.SYSTEM_SESSION_ID;

        // SYSTEM keys bypass the WorkspaceScoped cache to avoid
        // ContextNotActiveException during startup/boot
        VaultKey systemKey = getSystemMasterKey();
        try {
            AesGcmSivCipher.CipherResult result = cipher.encrypt(secret, systemKey, keyName);

            secretStore.save(new SecretDto(sessionId, keyName, 1, result.ciphertext(), result.nonce()));

            auditService.record(
                    AgentSentinels.SYSTEM_SESSION,
                    AgentSentinels.SYSTEM_USER,
                    AgentSentinels.SYSTEM_ROLE,
                    SecurityEventType.VAULT_STORE,
                    "systemKey=" + keyName);
            auditService.record(
                    AgentSentinels.SYSTEM_SESSION,
                    AgentSentinels.SYSTEM_USER,
                    AgentSentinels.SYSTEM_ROLE,
                    SecurityEventType.VAULT_ACCESS,
                    "systemKey=" + keyName + " op=STORE_SYSTEM");
        } catch (Exception e) {
            throw e;
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void removeSystemKey(String keyName) {
        secretStore.delete(VaultConstants.SYSTEM_SESSION_ID, keyName);

        auditService.record(
                AgentSentinels.SYSTEM_SESSION,
                AgentSentinels.SYSTEM_USER,
                AgentSentinels.SYSTEM_ROLE,
                SecurityEventType.VAULT_ACCESS,
                "systemKey=" + keyName + " op=DELETE_SYSTEM");
    }
}
