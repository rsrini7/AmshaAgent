package com.amsha.llm.providers.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.llm.api.oauth.ClaudeCodeOAuthTokenExtractor;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ClaudeCodeTokenResolverTest {

    private ClaudeCodeOAuthTokenExtractor tokenExtractor;
    private SecretVault secretVault;
    private ClaudeCodeTokenResolver resolver;

    @BeforeEach
    void setUp() {
        tokenExtractor = Mockito.mock(ClaudeCodeOAuthTokenExtractor.class);
        secretVault = Mockito.mock(SecretVault.class);
        resolver = new ClaudeCodeTokenResolver();
        resolver.tokenExtractor = tokenExtractor;
        resolver.secretVault = secretVault;
    }

    @Test
    void resolveToken_returnsExplicitKeyWhenProvided() {
        String token = resolver.resolveToken(Optional.of("explicit-api-key"));
        assertEquals("explicit-api-key", token);
        verify(secretVault, never()).retrieveSystemKey(any());
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void resolveToken_returnsVaultKeyWhenPresent() {
        when(secretVault.retrieveSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.of(new VaultKey("vault-token".getBytes(StandardCharsets.UTF_8))));

        String token = resolver.resolveToken();
        assertEquals("vault-token", token);
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void resolveToken_extractsAndStoresInVaultWhenNotCached() {
        when(secretVault.retrieveSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-oauth-token"));

        AtomicReference<byte[]> stored = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
                    stored.set(((byte[]) inv.getArgument(1)).clone());
                    return null;
                })
                .when(secretVault)
                .storeSystemKey(any(), any());

        String token = resolver.resolveToken();
        assertEquals("extracted-oauth-token", token);
        Assertions.assertArrayEquals("extracted-oauth-token".getBytes(StandardCharsets.UTF_8), stored.get());
        verify(secretVault).storeSystemKey(eq(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY), any(byte[].class));
    }

    @Test
    void resolveToken_throwsIllegalStateExceptionWhenNoCredentialsAvailable() {
        when(secretVault.retrieveSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
        when(tokenExtractor.extractToken()).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> resolver.resolveToken());
    }
}
