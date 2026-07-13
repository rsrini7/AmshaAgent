package com.amsha.mcp.transport;

import java.util.List;

/**
 * Transport-facing boundary for runtime MCP credential resolution.
 *
 * <p>Implementations own only ephemeral runtime references and just-in-time vault resolution. Durable metadata remains
 * in {@code McpConnectionStorePort}; secret values remain in {@code SecretVault}.</p>
 */
public interface McpConnectionCredentialPort {

    void replaceHeaders(String connectionId, String userId, List<HeaderSecretReference> refs);

    List<HeaderMetadata> listHeaderMetadata(String connectionId);

    ResolvedMcpHeaders resolveHeaders(String connectionId);

    void deleteHeaders(String connectionId);
}
