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

import com.amsha.llm.api.oauth.AgyOAuthTokenExtractor;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgyTokenResolverTest {

    private AgyOAuthTokenExtractor tokenExtractor;
    private SecretVault secretVault;
    private AgyTokenResolver resolver;

    @BeforeEach
    void setUp() {
        tokenExtractor = Mockito.mock(AgyOAuthTokenExtractor.class);
        secretVault = Mockito.mock(SecretVault.class);
        resolver = new AgyTokenResolver();
        resolver.tokenExtractor = tokenExtractor;
        resolver.secretVault = secretVault;
    }

    @Test
    void explicitKeyTakesPrecedence() {
        String token = resolver.resolveToken(Optional.of("explicit-agy-key"));
        assertEquals("explicit-agy-key", token);
        verify(secretVault, never()).retrieveSystemKey(any());
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void resolveTokenHandlesCliOrVault() {
        if (AgyCliExecutor.isAvailable()) {
            String token = resolver.resolveToken(Optional.empty());
            assertEquals("agy-cli-session", token);
        } else {
            byte[] tokenBytes = "vault-agy-token".getBytes(StandardCharsets.UTF_8);
            when(secretVault.retrieveSystemKey(VaultConstants.AGY_OAUTH_TOKEN_KEY))
                    .thenReturn(Optional.of(new VaultKey(tokenBytes)));

            String token = resolver.resolveToken(Optional.empty());
            assertEquals("vault-agy-token", token);
        }
    }

    @Test
    void extractorIsUsedAndCachedWhenVaultMisses() {
        if (!AgyCliExecutor.isAvailable()) {
            when(secretVault.retrieveSystemKey(VaultConstants.AGY_OAUTH_TOKEN_KEY))
                    .thenReturn(Optional.empty());
            when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-agy-token"));

            AtomicReference<byte[]> stored = new AtomicReference<>();
            Mockito.doAnswer(inv -> {
                        byte[] bytes = inv.getArgument(1);
                        stored.set(bytes.clone());
                        return null;
                    })
                    .when(secretVault)
                    .storeSystemKey(eq(VaultConstants.AGY_OAUTH_TOKEN_KEY), any(byte[].class));

            String token = resolver.resolveToken(Optional.empty());
            assertEquals("extracted-agy-token", token);
            Assertions.assertNotNull(stored.get());
            assertEquals("extracted-agy-token", new String(stored.get(), StandardCharsets.UTF_8));
        } else {
            String token = resolver.resolveToken(Optional.empty());
            assertEquals("agy-cli-session", token);
        }
    }
}
