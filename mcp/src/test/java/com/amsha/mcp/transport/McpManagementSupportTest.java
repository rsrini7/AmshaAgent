package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import com.amsha.core.audit.AuditService;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.security.VaultConstants;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionDto;

import org.junit.jupiter.api.Test;

class McpManagementSupportTest {

    private final McpManagementSupport support = new McpManagementSupport(
            mock(McpClientService.class),
            mock(McpConnectionLifecycleService.class),
            mock(DynamicToolCapabilityRegistry.class),
            mock(AuditService.class));

    @Test
    void acceptsRevisionBumpedByMatchingPendingApproval() {
        McpConnectionDto connection = connection(5, "task-1");

        assertEquals(5, support.checkApprovalRevision(connection, 4L, "task-1"));
    }

    @Test
    void rejectsConcurrentChangeAfterPendingApprovalWasCreated() {
        McpConnectionDto connection = connection(7, "task-1");

        assertThrows(McpRevisionConflictException.class, () -> support.checkApprovalRevision(connection, 4L, "task-1"));
    }

    @Test
    void rejectsApprovalForDifferentPendingTask() {
        McpConnectionDto connection = connection(5, "task-1");

        assertThrows(McpRevisionConflictException.class, () -> support.checkApprovalRevision(connection, 4L, "task-2"));
    }

    @Test
    void runsNetworkActionInsideUserVaultScope() {
        String activeScope = support.runInUserVaultScope("user-1", () -> {
            assertTrue(WorkspaceScopeHolder.isActive());
            return WorkspaceScopeHolder.getIfActive();
        });

        assertEquals(VaultConstants.USER_VAULT_SCOPE_PREFIX + "user-1", activeScope);
        assertFalse(WorkspaceScopeHolder.isActive());
    }

    private static McpConnectionDto connection(long revision, String taskId) {
        Instant now = Instant.now();
        return new McpConnectionDto(
                "connection-1",
                "user-1",
                "tapetide",
                McpTransportType.HTTP_SSE.name(),
                "https://example.test/mcp",
                "NETWORK",
                true,
                McpManagementService.STATE_PENDING_APPROVAL,
                taskId,
                revision,
                null,
                now,
                now);
    }
}
