package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CodexTokenResolverTest {

    private CodexOAuthTokenExtractor tokenExtractor;
    private SecretVault secretVault;
    private CodexTokenResolver resolver;

    @BeforeEach
    void setUp() {
        tokenExtractor = Mockito.mock(CodexOAuthTokenExtractor.class);
        secretVault = Mockito.mock(SecretVault.class);
        resolver = new CodexTokenResolver();
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
        when(secretVault.retrieveSystemKey(VaultConstants.CODEX_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.of(new VaultKey("vault-token".getBytes(StandardCharsets.UTF_8))));

        String token = resolver.resolveToken();
        assertEquals("vault-token", token);
        verify(tokenExtractor, never()).extractToken();
    }

    @Test
    void resolveToken_extractsAndStoresInVaultWhenNotCached() {
        when(secretVault.retrieveSystemKey(VaultConstants.CODEX_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
        when(tokenExtractor.extractToken()).thenReturn(Optional.of("extracted-oauth-token"));

        String token = resolver.resolveToken();
        assertEquals("extracted-oauth-token", token);
        verify(secretVault)
                .storeSystemKey(
                        eq(VaultConstants.CODEX_OAUTH_TOKEN_KEY),
                        eq("extracted-oauth-token".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void resolveToken_throwsWhenNoTokenAvailable() {
        when(secretVault.retrieveSystemKey(VaultConstants.CODEX_OAUTH_TOKEN_KEY))
                .thenReturn(Optional.empty());
        when(tokenExtractor.extractToken()).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> resolver.resolveToken());
        assertEquals(
                "Codex provider requires an active login. Please run 'codex login' in your local terminal first, or set AMSHA_CODEX_API_KEY.",
                ex.getMessage());
    }
}
