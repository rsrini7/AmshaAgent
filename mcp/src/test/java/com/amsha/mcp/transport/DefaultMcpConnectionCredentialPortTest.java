package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultKey;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DefaultMcpConnectionCredentialPortTest {

    SecretVault secretVault;
    DefaultMcpConnectionCredentialPort port;

    @BeforeEach
    void setUp() {
        secretVault = Mockito.mock(SecretVault.class);
        port = new DefaultMcpConnectionCredentialPort();
        port.secretVault = secretVault;
    }

    @Test
    void resolveHeadersResolvesVaultRefsJustInTime() {
        McpConnectionHeaderSecretRefDto ref = ref("ref-1", "authorization", "vault-auth");
        port.replaceHeaders("mcp-1", "user-1", List.of(HeaderSecretReference.from(ref)));
        when(secretVault.get("vault-auth", "user-1"))
                .thenReturn(Optional.of(new VaultKey("Bearer secret".getBytes(StandardCharsets.UTF_8))));

        try (ResolvedMcpHeaders headers = port.resolveHeaders("mcp-1")) {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.test"));
            headers.applyTo(builder);
            assertEquals(
                    "Bearer secret",
                    builder.build().headers().firstValue("authorization").orElseThrow());
        }
    }

    @Test
    void resolveHeadersFailsClosedWhenVaultRefMissing() {
        McpConnectionHeaderSecretRefDto ref = ref("ref-1", "authorization", "vault-auth");
        port.replaceHeaders("mcp-1", "user-1", List.of(HeaderSecretReference.from(ref)));
        when(secretVault.get("vault-auth", "user-1")).thenReturn(Optional.empty());

        assertThrows(McpConnectionException.class, () -> port.resolveHeaders("mcp-1"));
    }

    @Test
    void deleteHeadersRemovesRuntimeReferences() {
        port.replaceHeaders(
                "mcp-1", "user-1", List.of(HeaderSecretReference.from(ref("ref-1", "authorization", "vault-auth"))));
        port.deleteHeaders("mcp-1");

        try (ResolvedMcpHeaders headers = port.resolveHeaders("mcp-1")) {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.test"));
            headers.applyTo(builder);
            assertEquals(0, builder.build().headers().map().size());
        }
    }

    @Test
    void listHeaderMetadataReturnsOnlyNonSecretFields() {
        McpConnectionHeaderSecretRefDto ref = ref("ref-1", "authorization", "vault-auth");
        port.replaceHeaders("mcp-1", "user-1", List.of(HeaderSecretReference.from(ref)));

        List<HeaderMetadata> metadata = port.listHeaderMetadata("mcp-1");

        assertEquals(1, metadata.size());
        assertEquals("authorization", metadata.getFirst().headerName());
        assertEquals(true, metadata.getFirst().credentialConfigured());
        assertEquals(ref.createdAt(), metadata.getFirst().createdAt());
        assertEquals(ref.updatedAt(), metadata.getFirst().updatedAt());
    }

    private static McpConnectionHeaderSecretRefDto ref(String id, String headerName, String vaultKeyRef) {
        Instant now = Instant.now();
        return new McpConnectionHeaderSecretRefDto(id, "mcp-1", "user-1", headerName, vaultKeyRef, now, now);
    }
}
