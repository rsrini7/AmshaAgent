package com.amsha.llm.providers.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;
import com.amsha.llm.api.oauth.KimchiOAuthTokenExtractor;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class KimchiTokenResolverTest {

    private KimchiOAuthTokenExtractor tokenExtractor;
    private SecretVault secretVault;
    private KimchiTokenResolver resolver;

    @BeforeEach
    void setUp() {
        tokenExtractor = Mockito.mock(KimchiOAuthTokenExtractor.class);
        secretVault = Mockito.mock(SecretVault.class);
        resolver = new KimchiTokenResolver();
        resolver.tokenExtractor = tokenExtractor;
        resolver.secretVault = secretVault;
    }

    @Test
    void explicitKeyTakesPrecedence() {
        String token = resolver.resolveToken(Optional.of("explicit-kimchi-key"));
        assertEquals("explicit-kimchi-key", token);
        verify(secretVault, never()).retrieveSystemKey(any());
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void vaultCachedTokenIsUsedWhenPresent() {
        byte[] tokenBytes = "vault-kimchi-token".getBytes(StandardCharsets.UTF_8);
        when(secretVault.retrieveSystemKey(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.of(new VaultKey(tokenBytes)));

        String token = resolver.resolveToken(Optional.empty());
        assertEquals("vault-kimchi-token", token);
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void extractorIsUsedAndCachedWhenVaultMisses() {
        when(secretVault.retrieveSystemKey(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-kimchi-token"));

        AtomicReference<byte[]> stored = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
                    byte[] bytes = inv.getArgument(1);
                    stored.set(bytes.clone());
                    return null;
                })
                .when(secretVault)
                .storeSystemKey(eq(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY), any(byte[].class));

        String token = resolver.resolveToken(Optional.empty());
        assertEquals("extracted-kimchi-token", token);
        Assertions.assertNotNull(stored.get());
        assertEquals("extracted-kimchi-token", new String(stored.get(), StandardCharsets.UTF_8));
    }
}
