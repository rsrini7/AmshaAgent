package com.amsha.mcp.transport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class DefaultMcpConnectionCredentialPort implements McpConnectionCredentialPort {

    @Inject
    SecretVault secretVault;

    private final ConcurrentHashMap<String, HeaderVaultRefs> headerRefs = new ConcurrentHashMap<>();

    private record HeaderVaultRefs(String userId, List<HeaderSecretReference> refs) {}

    @Override
    public void replaceHeaders(String connectionId, String userId, List<HeaderSecretReference> refs) {
        if (refs == null || refs.isEmpty()) {
            headerRefs.remove(connectionId);
        } else {
            headerRefs.put(connectionId, new HeaderVaultRefs(userId, List.copyOf(refs)));
        }
    }

    @Override
    public List<HeaderMetadata> listHeaderMetadata(String connectionId) {
        HeaderVaultRefs refs = headerRefs.get(connectionId);
        if (refs == null || refs.refs().isEmpty()) return List.of();
        return refs.refs().stream()
                .map(ref -> new HeaderMetadata(ref.headerName(), true, ref.createdAt(), ref.updatedAt()))
                .toList();
    }

    @Override
    public ResolvedMcpHeaders resolveHeaders(String connectionId) {
        HeaderVaultRefs refs = headerRefs.get(connectionId);
        if (refs == null || refs.refs().isEmpty()) return ResolvedMcpHeaders.empty();
        List<ResolvedMcpHeaders.ResolvedMcpHeader> resolved = new ArrayList<>();
        try {
            for (HeaderSecretReference ref : refs.refs()) {
                try (VaultKey value = secretVault
                        .get(ref.vaultKeyRef(), refs.userId())
                        .orElseThrow(() -> new McpConnectionException("Missing MCP header credential"))) {
                    resolved.add(new ResolvedMcpHeaders.ResolvedMcpHeader(
                            ref.headerName(), Arrays.copyOf(value.get(), value.get().length)));
                }
            }
            return new ResolvedMcpHeaders(resolved);
        } catch (RuntimeException e) {
            new ResolvedMcpHeaders(resolved).close();
            throw e;
        }
    }

    @Override
    public void deleteHeaders(String connectionId) {
        headerRefs.remove(connectionId);
    }
}
