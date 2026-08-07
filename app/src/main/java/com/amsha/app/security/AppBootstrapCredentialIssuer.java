package com.amsha.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import com.amsha.core.security.BootstrapCredentialIssuer;
import com.amsha.core.security.IssuedCredential;
import com.amsha.core.security.PreparedBootstrapCredential;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;

import org.jboss.logging.Logger;

@ApplicationScoped
public class AppBootstrapCredentialIssuer implements BootstrapCredentialIssuer {

    private static final Logger log = Logger.getLogger(AppBootstrapCredentialIssuer.class);

    @Inject
    ApiCredentialStorePort apiCredentialStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Inject
    BootstrapStateStorePort bootstrapStateStore;

    @Override
    public PreparedBootstrapCredential prepare(String ownerId) {
        log.infof("Preparing bootstrap credential for owner=%s", ownerId);
        byte[] bytes = new byte[32];
        String rawKey;
        try {
            new SecureRandom().nextBytes(bytes);
            rawKey = "sk-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        String digest = apiKeyRegistry.computeDigest(rawKey);
        // Re-wrap the raw key into fresh mutable bytes so the prepared credential owns its lifecycle.
        byte[] rawKeyBytes = rawKey.getBytes(StandardCharsets.UTF_8);
        return new PreparedBootstrapCredential(ownerId, rawKey, rawKeyBytes, digest);
    }

    @Override
    public IssuedCredential activate(PreparedBootstrapCredential prepared, int expectedRevision) {
        log.infof(
                "Activating bootstrap credential for owner=%s expectedRevision=%d",
                prepared.ownerId(), expectedRevision);
        BootstrapStateDto state = bootstrapStateStore
                .getState()
                .orElseThrow(() -> new IllegalStateException("No bootstrap state for activation"));
        // CREDENTIAL_ISSUED is normal activation; CLAIMING is operator-only recovery for a
        // reserved-but-never-activated owner; CLAIMED is loopback-only local owner recovery.
        if ((!state.isCredentialIssued() && !state.isClaiming() && !state.isClaimed())
                || state.revision() != expectedRevision) {
            throw new IllegalStateException(
                    "Bootstrap reservation mismatch: expected CREDENTIAL_ISSUED/CLAIMING/CLAIMED@" + expectedRevision
                            + " but was " + state.status() + "@" + state.revision());
        }
        if (!state.ownerId().equals(prepared.ownerId())) {
            throw new IllegalStateException("Bootstrap reservation owner mismatch");
        }

        Instant now = Instant.now();
        ApiCredentialDto cred = new ApiCredentialDto(
                "bootstrap-cred", prepared.ownerId(), prepared.keyDigest(), "OWNER", 1, null, null, now, now);
        if (!apiCredentialStore.save(cred)) {
            throw new IllegalStateException("Bootstrap credential changed concurrently");
        }
        boolean transitioned = state.isClaiming()
                ? bootstrapStateStore.recordCredentialIssued(expectedRevision, prepared.keyDigest(), now)
                : bootstrapStateStore.replaceCredential(expectedRevision, prepared.keyDigest(), now, now);
        if (!transitioned) {
            throw new IllegalStateException("Failed to persist bootstrap credential state transition");
        }
        // Register in the transient cache only after the durable write succeeded.
        apiKeyRegistry.register(prepared.ownerId(), prepared.rawKey());
        return new IssuedCredential(prepared.rawKey(), prepared.keyDigest());
    }

    @Override
    public void revoke(String oldDigestHex) {
        apiKeyRegistry.unregisterByDigest(oldDigestHex);
    }
}
