package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.security.IssuedCredential;
import com.amsha.security.PreparedBootstrapCredential;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppBootstrapCredentialIssuerTest {

    private final ApiCredentialStorePort credentialStore = mock(ApiCredentialStorePort.class);
    private final ApiKeyRegistry registry = mock(ApiKeyRegistry.class);
    private final BootstrapStateStorePort stateStore = mock(BootstrapStateStorePort.class);
    private final AppBootstrapCredentialIssuer issuer = new AppBootstrapCredentialIssuer();

    @BeforeEach
    void setUp() {
        issuer.apiCredentialStore = credentialStore;
        issuer.apiKeyRegistry = registry;
        issuer.bootstrapStateStore = stateStore;
    }

    @Test
    void preparesActivatesAndRevokesCredential() {
        when(registry.computeDigest(any())).thenReturn("digest");
        PreparedBootstrapCredential prepared = issuer.prepare("owner");
        assertTrue(prepared.rawKey().startsWith("sk-"));
        assertEquals("digest", prepared.keyDigest());

        when(stateStore.getState())
                .thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, "owner", 3)));
        when(credentialStore.save(any())).thenReturn(true);
        when(stateStore.replaceCredential(eq(3), eq("digest"), any(), any())).thenReturn(true);

        IssuedCredential issued = issuer.activate(prepared, 3);
        assertEquals(prepared.rawKey(), issued.rawKey());
        verify(registry).register("owner", prepared.rawKey());

        issuer.revoke("old-digest");
        verify(registry).unregisterByDigest("old-digest");
    }

    @Test
    void activatesClaimingRecoveryAndRejectsInvalidReservations() {
        PreparedBootstrapCredential prepared =
                new PreparedBootstrapCredential("owner", "raw", new byte[] {1}, "digest");
        when(credentialStore.save(any())).thenReturn(true);
        when(stateStore.recordCredentialIssued(eq(4), eq("digest"), any())).thenReturn(true);
        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMING, "owner", 4)));
        issuer.activate(prepared, 4);

        when(stateStore.getState()).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> issuer.activate(prepared, 4));

        when(stateStore.getState()).thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CLAIMED, "owner", 4)));
        assertThrows(IllegalStateException.class, () -> issuer.activate(prepared, 4));

        when(stateStore.getState())
                .thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, "other", 4)));
        assertThrows(IllegalStateException.class, () -> issuer.activate(prepared, 4));
    }

    @Test
    void rejectsConcurrentCredentialAndStateChanges() {
        PreparedBootstrapCredential prepared =
                new PreparedBootstrapCredential("owner", "raw", new byte[] {1}, "digest");
        when(stateStore.getState())
                .thenReturn(Optional.of(state(BootstrapStateDto.STATUS_CREDENTIAL_ISSUED, "owner", 2)));
        when(credentialStore.save(any())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> issuer.activate(prepared, 2));

        when(credentialStore.save(any())).thenReturn(true);
        when(stateStore.replaceCredential(eq(2), eq("digest"), any(), any())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> issuer.activate(prepared, 2));
    }

    private static BootstrapStateDto state(String status, String owner, int revision) {
        return new BootstrapStateDto(
                "deployment", status, owner, "workspace", null, null, null, revision, Instant.now());
    }
}
