package com.amsha.core.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.Optional;

import com.amsha.security.api.SecretVault;

import io.quarkus.test.Mock;

/**
 * Provides a mock {@link SecretVault} for {@code amsha-core} Quarkus test contexts, where the
 * concrete vault implementation resides in the sibling {@code amsha-security} module
 * (extract-core-subsystems S7).
 */
@ApplicationScoped
public class TestSecretVaultProducer {

    @Produces
    @Mock
    @ApplicationScoped
    public SecretVault secretVault() {
        SecretVault vault = mock(SecretVault.class);
        when(vault.get(anyString())).thenReturn(Optional.empty());
        when(vault.get(anyString(), anyString())).thenReturn(Optional.empty());
        when(vault.getWorkspace(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(vault.retrieveSystemKey(anyString())).thenReturn(Optional.empty());
        return vault;
    }
}
