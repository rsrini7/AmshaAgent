package com.amsha.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Port for owner-scoped, non-secret outbound MCP connection metadata.
 *
 * <p>Header values are held only in {@code SecretVault}; this port persists opaque vault references.</p>
 */
public interface McpConnectionStorePort {

    void save(McpConnectionDto connection);

    Optional<McpConnectionDto> findByIdAndUserId(String id, String userId);

    Optional<McpConnectionDto> findByNameAndUserId(String name, String userId);

    List<McpConnectionDto> listByUserId(String userId);

    List<McpConnectionDto> listEnabled();

    List<McpConnectionDto> listPendingApprovals();

    boolean updateStateIfRevision(McpConnectionDto connection, long expectedRevision);

    List<McpConnectionToolPolicyDto> listToolPolicies(String connectionId, String userId);

    void replaceToolPolicies(String connectionId, String userId, List<McpConnectionToolPolicyDto> policies);

    int setToolEnabled(String connectionId, String userId, String toolName, boolean enabled);

    int deleteByIdAndUserId(String id, String userId);

    void replaceHeaderSecretRefs(String connectionId, String userId, List<McpConnectionHeaderSecretRefDto> references);

    List<McpConnectionHeaderSecretRefDto> listHeaderSecretRefs(String connectionId, String userId);

    void saveCleanupFailure(McpConnectionCleanupFailureDto failure);

    List<McpConnectionCleanupFailureDto> listCleanupFailures();

    int deleteCleanupFailure(String id, String userId);
}
