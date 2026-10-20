package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.tool.McpTransportType;
import com.amsha.persistence.api.McpConnectionCleanupFailureDto;
import com.amsha.persistence.api.McpConnectionDto;
import com.amsha.persistence.api.McpConnectionHeaderSecretRefDto;
import com.amsha.persistence.api.McpConnectionStorePort;
import com.amsha.persistence.api.McpConnectionToolPolicyDto;
import com.amsha.security.api.SecretVault;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCategory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Startup reconciliation, durable reconnect, and deferred credential cleanup.
 *
 * <p>The orchestrator is package-private and takes five {@link Supplier}s, so it is built by hand with no CDI
 * container. {@link McpConnectionLifecycleOrchestrator#runInUserVaultScope} is exercised for real: it is
 * null-safe when {@code Arc.container()} is null.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S1192")
class McpConnectionLifecycleOrchestratorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    McpConnectionStorePort store;

    @Mock
    SecretVault vault;

    @Mock
    AuditService audit;

    @Mock
    McpClientService clientService;

    @Mock
    DynamicToolCapabilityRegistry toolRegistry;

    // --------------------------------------------------------------- fixtures

    private McpConnectionLifecycleOrchestrator orchestrator() {
        return new McpConnectionLifecycleOrchestrator(
                () -> Optional.of(store), () -> vault, () -> audit, () -> clientService, () -> toolRegistry);
    }

    private static McpConnectionDto connection(String name, String transport, boolean enabled) {
        return new McpConnectionDto(
                "id-" + name,
                "user-1",
                name,
                transport,
                "https://host/" + name,
                ToolCategory.NETWORK.name(),
                enabled,
                "CONNECTED",
                null,
                3L,
                null,
                T0,
                T0);
    }

    private static McpConnectionCleanupFailureDto cleanupFailure(String id) {
        return new McpConnectionCleanupFailureDto(
                id, "conn-" + id, "user-1", "Authorization", "vault-" + id, "cleanup failed", 1, T0, T0);
    }

    private static McpConnectionHeaderSecretRefDto headerRef(String id, String headerName) {
        return new McpConnectionHeaderSecretRefDto(id, "conn-1", "user-1", headerName, "vault-" + id, T0, T0);
    }

    private static McpConnectionToolPolicyDto policy(String toolName, boolean enabled) {
        return new McpConnectionToolPolicyDto(
                "pol-" + toolName, "conn-1", "user-1", toolName, true, enabled, "d", "{}", "h", T0, 1L, T0);
    }

    private static DynamicToolCapability capability(String toolName, String serverName) {
        return new DynamicToolCapability(toolName, "desc", serverName, McpTransportType.HTTP_SSE, false, "{}");
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    // ------------------------------------------------------------ startup timer

    @Test
    void onStartupArmsTheTimerOnceAndShutdownCancelsIt() throws Exception {
        McpConnectionLifecycleOrchestrator orchestrator = orchestrator();
        assertNull(readField(orchestrator, "startupReconnectTimer"), "nothing must be armed before startup");

        orchestrator.onStartup();
        Object armed = readField(orchestrator, "startupReconnectTimer");
        assertNotNull(armed, "startup must arm a reconnect timer");

        orchestrator.onStartup();
        assertSame(
                armed,
                readField(orchestrator, "startupReconnectTimer"),
                "a second onStartup must not arm a second timer");

        orchestrator.onShutdown();
        assertNull(readField(orchestrator, "startupReconnectTimer"), "shutdown must cancel and clear the timer");
        assertTrue((Boolean) readField(orchestrator, "shuttingDown"), "shutdown must latch the flag");
    }

    @Test
    void onStartupAfterShutdownIsInert() throws Exception {
        McpConnectionLifecycleOrchestrator orchestrator = orchestrator();
        orchestrator.onShutdown();

        orchestrator.onStartup();

        assertNull(
                readField(orchestrator, "startupReconnectTimer"),
                "a shut-down orchestrator must never arm a reconnect timer");
    }

    @Test
    void onShutdownIsIdempotent() {
        McpConnectionLifecycleOrchestrator orchestrator = orchestrator();

        orchestrator.onShutdown();
        orchestrator.onShutdown();

        verifyNoInteractions(store, vault, audit, clientService, toolRegistry);
    }

    // ------------------------------------------------------ reconciliation entry

    @Test
    void runStartupReconciliationIsANoOpOnceShuttingDown() {
        McpConnectionLifecycleOrchestrator orchestrator = orchestrator();
        orchestrator.onShutdown();

        orchestrator.runStartupReconciliation();

        verifyNoInteractions(store, vault, audit, clientService, toolRegistry);
    }

    @Test
    void runStartupReconciliationRunsCleanupApprovalsAndReconnectInOrder() {
        when(store.listCleanupFailures()).thenReturn(List.of(cleanupFailure("c1")));
        when(store.listPendingApprovals()).thenReturn(List.of());
        when(store.listEnabled()).thenReturn(List.of(connection("srv", "STDIO", true)));

        orchestrator().runStartupReconciliation();

        // cleanup phase, then approvals, then reconnect (the STDIO row is skipped but still listed)
        verify(store).deleteCleanupFailure("c1", "user-1");
        verify(store).listPendingApprovals();
        verify(store).listEnabled();
        verify(vault).remove("vault-c1", "user-1");
    }

    @Test
    void runStartupReconciliationStopsAfterCleanupWhenShutdownIsRequestedMidRun() {
        McpConnectionLifecycleOrchestrator orchestrator = orchestrator();
        doAnswer(invocation -> {
                    orchestrator.onShutdown();
                    return List.of();
                })
                .when(store)
                .listCleanupFailures();

        orchestrator.runStartupReconciliation();

        verify(store, never()).listPendingApprovals();
        verify(store, never()).listEnabled();
    }

    @Test
    void runStartupReconciliationStopsAfterApprovalsWhenShutdownIsRequestedMidRun() {
        McpConnectionLifecycleOrchestrator orchestrator = orchestrator();
        when(store.listCleanupFailures()).thenReturn(List.of());
        doAnswer(invocation -> {
                    orchestrator.onShutdown();
                    return List.of();
                })
                .when(store)
                .listPendingApprovals();

        orchestrator.runStartupReconciliation();

        verify(store, never()).listEnabled();
    }

    @Test
    void runStartupReconciliationSwallowsAFailureWithoutPropagating() {
        when(store.listCleanupFailures()).thenThrow(new IllegalStateException("store down"));

        // must not propagate: startup reconciliation is best-effort
        orchestrator().runStartupReconciliation();

        verify(store).listCleanupFailures();
    }

    @Test
    void runStartupReconciliationStaysSilentWhenAFailureArrivesDuringShutdown() {
        McpConnectionLifecycleOrchestrator orchestrator = orchestrator();
        doAnswer(invocation -> {
                    orchestrator.onShutdown();
                    throw new IllegalStateException("store down");
                })
                .when(store)
                .listCleanupFailures();

        // the catch block checks `shuttingDown` before logging; a throw must still not escape
        orchestrator.runStartupReconciliation();

        verify(store).listCleanupFailures();
    }

    // ---------------------------------------------------- cleanup retry phase

    @Test
    void retryCleanupFailuresReturnsZeroWhenTheStoreIsAbsent() {
        McpConnectionLifecycleOrchestrator orchestrator = new McpConnectionLifecycleOrchestrator(
                Optional::<McpConnectionStorePort>empty,
                () -> vault,
                () -> audit,
                () -> clientService,
                () -> toolRegistry);

        assertEquals(0, orchestrator.retryCleanupFailures());
        verifyNoInteractions(vault, audit);
    }

    @Test
    void retryCleanupFailuresRemovesTheSecretDeletesTheRecordAndAudits() {
        when(store.listCleanupFailures()).thenReturn(List.of(cleanupFailure("c1")));

        assertEquals(1, orchestrator().retryCleanupFailures());

        verify(vault).remove("vault-c1", "user-1");
        verify(store).deleteCleanupFailure("c1", "user-1");
        verify(audit)
                .recordMandatory(
                        eq(AgentSentinels.SYSTEM_SESSION),
                        eq("user-1"),
                        eq(AgentSentinels.SYSTEM_ROLE),
                        eq(SecurityEventType.MCP_CREDENTIAL_REMOVED),
                        contains("cleanupRetry=true"));
        verify(store, never()).saveCleanupFailure(any());
    }

    @Test
    void retryCleanupFailuresToleratesAnAbsentVaultAndAudit() {
        when(store.listCleanupFailures()).thenReturn(List.of(cleanupFailure("c1")));
        McpConnectionLifecycleOrchestrator orchestrator = new McpConnectionLifecycleOrchestrator(
                () -> Optional.of(store), () -> null, () -> null, () -> clientService, () -> toolRegistry);

        assertEquals(1, orchestrator.retryCleanupFailures());

        verify(store).deleteCleanupFailure("c1", "user-1");
    }

    @Test
    void retryCleanupFailuresBumpsTheAttemptCountAndRequeuesWhenRemovalFails() {
        when(store.listCleanupFailures()).thenReturn(List.of(cleanupFailure("c1")));
        doThrow(new IllegalStateException("vault locked")).when(vault).remove("vault-c1", "user-1");

        assertEquals(0, orchestrator().retryCleanupFailures());

        ArgumentCaptor<McpConnectionCleanupFailureDto> requeued =
                ArgumentCaptor.forClass(McpConnectionCleanupFailureDto.class);
        verify(store).saveCleanupFailure(requeued.capture());
        assertEquals("c1", requeued.getValue().id(), "the same retry row must be updated, not replaced");
        assertEquals(2, requeued.getValue().attempts(), "a failed retry must increment attempts");
        assertEquals("Authorization", requeued.getValue().headerName());
        verify(store, never()).deleteCleanupFailure(anyString(), anyString());
        verify(audit)
                .recordMandatory(
                        eq(AgentSentinels.SYSTEM_SESSION),
                        eq("user-1"),
                        eq(AgentSentinels.SYSTEM_ROLE),
                        eq(SecurityEventType.MCP_CREDENTIAL_CLEANUP_FAILED),
                        contains("attempts=2"));
    }

    @Test
    void retryCleanupFailuresFallsBackToADefaultReasonWhenTheFailureCarriesNoMessage() {
        when(store.listCleanupFailures()).thenReturn(List.of(cleanupFailure("c1")));
        doThrow(new IllegalStateException("")).when(vault).remove("vault-c1", "user-1");

        assertEquals(0, orchestrator().retryCleanupFailures());

        ArgumentCaptor<McpConnectionCleanupFailureDto> requeued =
                ArgumentCaptor.forClass(McpConnectionCleanupFailureDto.class);
        verify(store).saveCleanupFailure(requeued.capture());
        assertFalse(
                requeued.getValue().reason().isBlank(),
                "a message-less failure must still produce a usable retry reason");
    }

    // -------------------------------------------------------- approvals phase

    @Test
    void reconcilePendingApprovalsReturnsZeroWhenTheStoreIsAbsent() {
        McpConnectionLifecycleOrchestrator orchestrator = new McpConnectionLifecycleOrchestrator(
                Optional::<McpConnectionStorePort>empty,
                () -> vault,
                () -> audit,
                () -> clientService,
                () -> toolRegistry);

        assertEquals(0, orchestrator.reconcilePendingApprovals());
    }

    @Test
    void reconcilePendingApprovalsDisconnectsAnEnabledConnectionAndAuditsTheDenial() {
        when(store.listPendingApprovals()).thenReturn(List.of(connection("srv", "HTTP_SSE", true)));
        when(store.updateStateIfRevision(any(), eq(3L))).thenReturn(true);

        assertEquals(1, orchestrator().reconcilePendingApprovals());

        ArgumentCaptor<McpConnectionDto> saved = ArgumentCaptor.forClass(McpConnectionDto.class);
        verify(store).updateStateIfRevision(saved.capture(), eq(3L));
        assertEquals("DISCONNECTED", saved.getValue().state(), "an enabled row loses its approval, not its enablement");
        assertEquals("APPROVAL_LOST_ON_RESTART", saved.getValue().lastErrorCode());
        assertEquals(4L, saved.getValue().revision(), "the optimistic-lock revision must advance");
        verify(audit)
                .recordMandatory(
                        eq(AgentSentinels.SYSTEM_SESSION),
                        eq("user-1"),
                        eq(AgentSentinels.SYSTEM_ROLE),
                        eq(SecurityEventType.APPROVAL_DENIED),
                        contains("APPROVAL_LOST_ON_RESTART"));
    }

    @Test
    void reconcilePendingApprovalsPausesADisabledConnection() {
        when(store.listPendingApprovals()).thenReturn(List.of(connection("srv", "HTTP_SSE", false)));
        when(store.updateStateIfRevision(any(), eq(3L))).thenReturn(true);

        assertEquals(1, orchestrator().reconcilePendingApprovals());

        ArgumentCaptor<McpConnectionDto> saved = ArgumentCaptor.forClass(McpConnectionDto.class);
        verify(store).updateStateIfRevision(saved.capture(), eq(3L));
        assertEquals("PAUSED", saved.getValue().state(), "a disabled row must stay paused, never reconnectable");
    }

    @Test
    void reconcilePendingApprovalsSkipsRowsThatLostTheRevisionRace() {
        when(store.listPendingApprovals()).thenReturn(List.of(connection("srv", "HTTP_SSE", true)));
        when(store.updateStateIfRevision(any(), eq(3L))).thenReturn(false);

        assertEquals(0, orchestrator().reconcilePendingApprovals(), "a lost race is not a reconciliation");

        verifyNoInteractions(audit);
    }

    // --------------------------------------------------------- reconnect phase

    @Test
    void reconnectEnabledConnectionsReturnsZeroWhenTheStoreIsAbsent() {
        McpConnectionLifecycleOrchestrator orchestrator = new McpConnectionLifecycleOrchestrator(
                Optional::<McpConnectionStorePort>empty,
                () -> vault,
                () -> audit,
                () -> clientService,
                () -> toolRegistry);

        assertEquals(0, orchestrator.reconnectEnabledConnections());
    }

    @Test
    void reconnectEnabledConnectionsSkipsNonHttpTransports() {
        when(store.listEnabled()).thenReturn(List.of(connection("stdio-srv", "STDIO", true)));

        assertEquals(0, orchestrator().reconnectEnabledConnections());

        verify(store, never()).save(any());
        verifyNoInteractions(clientService);
    }

    @Test
    void reconnectEnabledConnectionsRestoresAnHttpConnectionAndDisablesToolsThatVanished() {
        when(store.listEnabled()).thenReturn(List.of(connection("http-srv", "HTTP_SSE", true)));
        when(store.listToolPolicies("id-http-srv", "user-1"))
                .thenReturn(List.of(policy("tool-a", true), policy("tool-b", true)));
        when(store.listHeaderSecretRefs("id-http-srv", "user-1")).thenReturn(List.of(headerRef("r1", "Authorization")));
        when(toolRegistry.getAll("user-1"))
                .thenReturn(List.of(capability("tool-a", "http-srv"), capability("other", "another-srv")));

        assertEquals(1, orchestrator().reconnectEnabledConnections());

        verify(clientService)
                .configureHeaderSecretRefs("http-srv", "user-1", List.of(headerRef("r1", "Authorization")));
        verify(clientService)
                .reconnectDurableForUser(
                        "http-srv",
                        "user-1",
                        "https://host/http-srv",
                        ToolCategory.NETWORK,
                        Map.of(),
                        Set.of("tool-a", "tool-b"));

        // the stale registration for "tool-a" on this server is dropped; "other" belongs elsewhere
        verify(toolRegistry).deregister("user-1", "tool-a");
        verify(toolRegistry, never()).deregister("user-1", "other");

        // "tool-b" is enabled but absent from the registry, so it must be switched off; "tool-a" is still
        // registered and must be left alone.
        verify(store).setToolEnabled("id-http-srv", "user-1", "tool-b", false);
        verify(store, never()).setToolEnabled(anyString(), anyString(), eq("tool-a"), anyBoolean());
        // a tool belonging to a different server must never be touched
        verify(store, never()).setToolEnabled(anyString(), anyString(), eq("other"), anyBoolean());

        ArgumentCaptor<McpConnectionDto> saved = ArgumentCaptor.forClass(McpConnectionDto.class);
        verify(store).save(saved.capture());
        assertEquals("CONNECTED", saved.getValue().state());
        assertEquals(4L, saved.getValue().revision());
        assertNull(saved.getValue().lastErrorCode());
        verify(audit)
                .recordMandatory(
                        eq(AgentSentinels.SYSTEM_SESSION),
                        eq("user-1"),
                        eq(AgentSentinels.SYSTEM_ROLE),
                        eq(SecurityEventType.MCP_SERVER_CONNECTED),
                        contains("reconnect=true"));
    }

    @Test
    void reconnectEnabledConnectionsToleratesAnAbsentClientServiceAndAudit() {
        when(store.listEnabled()).thenReturn(List.of(connection("http-srv", "HTTP_SSE", true)));
        McpConnectionLifecycleOrchestrator orchestrator = new McpConnectionLifecycleOrchestrator(
                () -> Optional.of(store), () -> vault, () -> null, () -> null, () -> toolRegistry);

        assertEquals(1, orchestrator.reconnectEnabledConnections());

        ArgumentCaptor<McpConnectionDto> saved = ArgumentCaptor.forClass(McpConnectionDto.class);
        verify(store).save(saved.capture());
        assertEquals("CONNECTED", saved.getValue().state());
    }

    @Test
    void reconnectEnabledConnectionsToleratesAnAbsentToolRegistry() {
        when(store.listEnabled()).thenReturn(List.of(connection("http-srv", "HTTP_SSE", true)));
        McpConnectionLifecycleOrchestrator orchestrator = new McpConnectionLifecycleOrchestrator(
                () -> Optional.of(store), () -> vault, () -> audit, () -> clientService, () -> null);

        assertEquals(1, orchestrator.reconnectEnabledConnections());

        verify(store, never()).setToolEnabled(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void reconnectEnabledConnectionsMarksDegradedAndClearsStateWhenTheHandshakeFails() {
        when(store.listEnabled()).thenReturn(List.of(connection("http-srv", "HTTP_SSE", true)));
        doThrow(new McpConnectionException("handshake rejected"))
                .when(clientService)
                .reconnectDurableForUser(anyString(), anyString(), anyString(), any(), any(), any());

        assertEquals(0, orchestrator().reconnectEnabledConnections());

        verify(clientService).clearHttpConnectionState("http-srv", "user-1");

        ArgumentCaptor<McpConnectionDto> saved = ArgumentCaptor.forClass(McpConnectionDto.class);
        verify(store).save(saved.capture());
        assertEquals("DEGRADED", saved.getValue().state());
        assertEquals("HANDSHAKE_FAILED", saved.getValue().lastErrorCode());
        assertEquals(4L, saved.getValue().revision(), "a failed reconnect still advances the revision");
        verify(audit)
                .recordMandatory(
                        eq(AgentSentinels.SYSTEM_SESSION),
                        eq("user-1"),
                        eq(AgentSentinels.SYSTEM_ROLE),
                        eq(SecurityEventType.MCP_CONNECTION_VALIDATION_FAILED),
                        contains("reconnect=true"));
    }

    @Test
    void reconnectEnabledConnectionsToleratesAnAbsentClientServiceOnTheFailurePath() {
        when(store.listEnabled()).thenReturn(List.of(connection("http-srv", "HTTP_SSE", true)));
        when(store.listToolPolicies("id-http-srv", "user-1")).thenThrow(new IllegalStateException("store read failed"));
        McpConnectionLifecycleOrchestrator orchestrator = new McpConnectionLifecycleOrchestrator(
                () -> Optional.of(store), () -> vault, () -> audit, () -> null, () -> toolRegistry);

        // the store read throws inside the try, so the row degrades without a client to clean up
        assertEquals(0, orchestrator.reconnectEnabledConnections());

        ArgumentCaptor<McpConnectionDto> saved = ArgumentCaptor.forClass(McpConnectionDto.class);
        verify(store).save(saved.capture());
        assertEquals("DEGRADED", saved.getValue().state());
        assertEquals("MCP_RECONNECT_FAILED", saved.getValue().lastErrorCode());
    }

    @Test
    void reconnectEnabledConnectionsToleratesAnAbsentAuditOnTheFailurePath() {
        when(store.listEnabled()).thenReturn(List.of(connection("http-srv", "HTTP_SSE", true)));
        doThrow(new McpConnectionException("handshake rejected"))
                .when(clientService)
                .reconnectDurableForUser(anyString(), anyString(), anyString(), any(), any(), any());
        McpConnectionLifecycleOrchestrator orchestrator = new McpConnectionLifecycleOrchestrator(
                () -> Optional.of(store), () -> vault, () -> null, () -> clientService, () -> toolRegistry);

        assertEquals(0, orchestrator.reconnectEnabledConnections());

        ArgumentCaptor<McpConnectionDto> saved = ArgumentCaptor.forClass(McpConnectionDto.class);
        verify(store).save(saved.capture());
        assertEquals("DEGRADED", saved.getValue().state());
        assertEquals("HANDSHAKE_FAILED", saved.getValue().lastErrorCode());
    }

    @Test
    void classifyReconnectFailureSeparatesHandshakeFailuresFromEverythingElse() {
        assertEquals(
                "HANDSHAKE_FAILED",
                McpConnectionLifecycleOrchestrator.classifyReconnectFailure(
                        new McpConnectionException("handshake rejected")));
        assertEquals(
                "MCP_RECONNECT_FAILED",
                McpConnectionLifecycleOrchestrator.classifyReconnectFailure(new IllegalStateException("boom")));
    }

    // --------------------------------------------------- replaced-secret cleanup

    @Test
    void removeReplacedSecretsRemovesOnlyRefsThatAreNoLongerPresent() {
        McpConnectionLifecycleOrchestrator orchestrator = orchestrator();

        orchestrator.removeReplacedSecrets(
                store,
                "user-1",
                List.of(headerRef("gone", "X-Gone"), headerRef("kept", "X-Kept")),
                List.of(headerRef("kept", "X-Kept")));

        verify(vault).remove("vault-gone", "user-1");
        verify(vault, never()).remove("vault-kept", "user-1");
        verify(audit, times(1))
                .recordMandatory(
                        eq(AgentSentinels.SYSTEM_SESSION),
                        eq("user-1"),
                        eq(AgentSentinels.SYSTEM_ROLE),
                        eq(SecurityEventType.MCP_CREDENTIAL_REMOVED),
                        contains("X-Gone"));
    }

    @Test
    void removeVaultSecretOrRecordFailureQueuesARetryWhenTheVaultFails() {
        doThrow(new IllegalStateException("vault locked")).when(vault).remove("vault-r1", "user-1");

        orchestrator().removeVaultSecretOrRecordFailure(store, "user-1", headerRef("r1", "Authorization"));

        ArgumentCaptor<McpConnectionCleanupFailureDto> queued =
                ArgumentCaptor.forClass(McpConnectionCleanupFailureDto.class);
        verify(store).saveCleanupFailure(queued.capture());
        assertEquals("conn-1", queued.getValue().connectionId());
        assertEquals("Authorization", queued.getValue().headerName());
        assertEquals("vault-r1", queued.getValue().vaultKeyRef());
        assertEquals(1, queued.getValue().attempts(), "a freshly queued cleanup starts at attempt 1");
        verify(audit)
                .recordMandatory(
                        eq(AgentSentinels.SYSTEM_SESSION),
                        eq("user-1"),
                        eq(AgentSentinels.SYSTEM_ROLE),
                        eq(SecurityEventType.MCP_CREDENTIAL_CLEANUP_FAILED),
                        contains("attempts=1"));
    }

    @Test
    void removeVaultSecretOrRecordFailureStillQueuesWhenTheAuditServiceIsAbsent() {
        doThrow(new IllegalStateException("vault locked")).when(vault).remove("vault-r1", "user-1");
        McpConnectionLifecycleOrchestrator orchestrator = new McpConnectionLifecycleOrchestrator(
                () -> Optional.of(store), () -> vault, () -> null, () -> clientService, () -> toolRegistry);

        orchestrator.removeVaultSecretOrRecordFailure(store, "user-1", headerRef("r1", "Authorization"));

        verify(store).saveCleanupFailure(any());
    }

    @Test
    void removeVaultSecretOrRecordFailureToleratesAnAbsentVaultAndAudit() {
        McpConnectionLifecycleOrchestrator orchestrator = new McpConnectionLifecycleOrchestrator(
                () -> Optional.of(store), () -> null, () -> null, () -> clientService, () -> toolRegistry);

        orchestrator.removeVaultSecretOrRecordFailure(store, "user-1", headerRef("r1", "Authorization"));

        verify(store, never()).saveCleanupFailure(any());
    }
}
