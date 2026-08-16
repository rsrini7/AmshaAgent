package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.routing.provider.NetworkPolicyEnforcer;
import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultKey;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.WorkspaceConnectionGrantDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantStorePort;

import org.jboss.logging.Logger;

/**
 * Resolves a closable {@link ResolvedCredential} for a connection under the current identity
 * context. User-scoped connections read their {@code USER:} vault partition; workspace-scoped
 * connections require an active workspace grant and read the {@code WORKSPACE:} partition.
 *
 * <p>The resolver never logs, caches, or retains the plaintext key beyond the returned view.</p>
 */
@ApplicationScoped
public class ModelProfileCredentialResolver {

    private static final Logger log = Logger.getLogger(ModelProfileCredentialResolver.class);

    @Inject
    SecretVault vault;

    @Inject
    LlmProfileStorePort profileStore;

    @Inject
    WorkspaceConnectionGrantStorePort grantStore;

    @Inject
    ActiveWorkspaceContext activeWorkspaceContext;

    @Inject
    NetworkPolicyEnforcer networkPolicyEnforcer;

    /**
     * Resolves the credential for a connection. Returns empty when the acting user is not
     * authorized (missing workspace membership/grant, or unknown scope).
     */
    public Optional<ResolvedCredential> resolve(LlmConnectionDto connection, String actorUserId) {
        List<LlmConnectionSecretRefDto> refs = profileStore.listSecretRefs(connection.id());
        if (refs.isEmpty()) {
            log.debugf("No secret refs for connection %s", connection.id());
            return Optional.empty();
        }
        String vaultRef = refs.get(0).vaultRef();
        byte[] keyBytes =
                switch (connection.scopeType().toUpperCase()) {
                    case "WORKSPACE" ->
                        resolveWorkspaceKey(connection.scopeId(), actorUserId, vaultRef, connection.id());
                    case "USER" -> resolveUserKey(actorUserId, vaultRef);
                    case "SYSTEM" -> resolveSystemKey(vaultRef);
                    default -> {
                        log.warnf("Unknown connection scope type: %s", connection.scopeType());
                        yield null;
                    }
                };
        if (keyBytes == null) {
            return Optional.empty();
        }
        ProviderBaseUrl baseUrl = ProviderBaseUrl.forAdapter(connection.providerAdapterId());
        String resolvedBaseUrl = baseUrl.resolve(connection.customBaseUrl());
        // Enforce the connection's outbound-network policy before any network call would be made.
        if (networkPolicyEnforcer != null) {
            networkPolicyEnforcer.assertEgressAllowed(connection, resolvedBaseUrl);
        }
        return Optional.of(new ResolvedCredential(connection.providerAdapterId(), resolvedBaseUrl, keyBytes));
    }

    private byte[] resolveUserKey(String actorUserId, String vaultRef) {
        Optional<VaultKey> key = vault.get(vaultRef, actorUserId);
        return key.map(this::readAndClose).orElse(null);
    }

    private byte[] resolveSystemKey(String vaultRef) {
        // SYSTEM-scoped connections reference operator-managed system-vault material (e.g. the
        // legacy LLM_*_KEY migration). They must read the SYSTEM vault partition, not the acting
        // user's partition, otherwise the behavior-preserving SYSTEM-profile migration fails.
        Optional<VaultKey> key = vault.retrieveSystemKey(vaultRef);
        return key.map(this::readAndClose).orElse(null);
    }

    private byte[] resolveWorkspaceKey(String workspaceId, String actorUserId, String vaultRef, String connectionId) {
        // The grant authorizes the (workspace, connection) pair. A grant must exist and be ACTIVE;
        // mere active-workspace membership is not sufficient — every member would otherwise be able
        // to use every workspace-scoped connection regardless of the grant record.
        Optional<WorkspaceConnectionGrantDto> grant =
                grantStore.findByWorkspaceAndConnection(workspaceId, connectionId);
        if (grant.isEmpty() || !"ACTIVE".equalsIgnoreCase(grant.get().state())) {
            log.warnf(
                    "Actor %s not authorized for workspace connection %s: no active grant for workspace %s",
                    actorUserId, connectionId, workspaceId);
            return null;
        }
        // Defense in depth: the acting user must currently be in the granted workspace. Prefer the
        // workspace id propagated through the context-aware executor; fall back to the request-scoped
        // ActiveWorkspaceContext only when a request context is active (avoids ContextNotActiveException
        // in async agent/channel/cron execution).
        boolean inWorkspace =
                resolveActiveWorkspaceId().map(ws -> ws.equals(workspaceId)).orElse(false);
        if (!inWorkspace) {
            log.warnf(
                    "Actor %s not in workspace %s; refusing workspace connection %s",
                    actorUserId, workspaceId, connectionId);
            return null;
        }
        Optional<VaultKey> key = vault.getWorkspace(vaultRef, workspaceId, actorUserId);
        return key.map(this::readAndClose).orElse(null);
    }

    private byte[] readAndClose(VaultKey key) {
        try (VaultKey k = key) {
            byte[] bytes = k.get();
            return bytes.clone();
        }
    }

    /**
     * Resolves the active identity workspace id for authorization. Prefers the value propagated
     * through the context-aware executor / {@code WorkspaceContext.run} chain, then falls back to
     * the {@code @RequestScoped} {@code ActiveWorkspaceContext} only when a request context is
     * active. This avoids the {@code ContextNotActiveException} thrown by the request-scoped bean
     * outside an HTTP request while preserving request-time workspace selection.
     */
    private Optional<String> resolveActiveWorkspaceId() {
        Optional<String> propagated = WorkspaceContext.activeWorkspaceId();
        if (propagated.isPresent()) {
            return propagated;
        }
        try {
            return activeWorkspaceContext.workspaceId();
        } catch (ContextNotActiveException _) {
            return Optional.empty();
        }
    }

    /** Maps known provider adapter IDs to their default OpenAI-compatible base URLs. */
    enum ProviderBaseUrl {
        GROQ(ModelDefaults.DEFAULT_GROQ_BASE_URL),
        OPENAI(ModelDefaults.DEFAULT_OPENAI_BASE_URL),
        OPENROUTER(ModelDefaults.DEFAULT_OPENROUTER_BASE_URL),
        CEREBRAS(ModelDefaults.DEFAULT_CEREBRAS_BASE_URL),
        SARVAM(ModelDefaults.DEFAULT_SARVAM_BASE_URL),
        OPENCODE(ModelDefaults.DEFAULT_OPENCODE_BASE_URL),
        HUGGINGFACE(ModelDefaults.DEFAULT_HUGGINGFACE_BASE_URL),
        NVIDIA(ModelDefaults.DEFAULT_NVIDIA_BASE_URL),
        GEMINI(ModelDefaults.DEFAULT_GEMINI_BASE_URL),
        OLLAMA(ModelDefaults.DEFAULT_OLLAMA_BASE_URL),
        OMLX(ModelDefaults.DEFAULT_OMLX_BASE_URL),
        LLAMACPP(ModelDefaults.DEFAULT_LLAMACPP_BASE_URL),
        GENERIC_OPENAI("");

        private final String defaultUrl;

        ProviderBaseUrl(String defaultUrl) {
            this.defaultUrl = defaultUrl;
        }

        static ProviderBaseUrl forAdapter(String adapterId) {
            if (adapterId == null) return GENERIC_OPENAI;
            try {
                return ProviderBaseUrl.valueOf(adapterId.toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException _) {
                return GENERIC_OPENAI;
            }
        }

        String resolve(String customBaseUrl) {
            if (customBaseUrl != null && !customBaseUrl.isBlank()) return customBaseUrl;
            return defaultUrl;
        }
    }
}
