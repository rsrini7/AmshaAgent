package com.amsha.core.tools;

import static com.amsha.core.audit.SecurityEventType.TOOL_BLOCKED;
import static com.amsha.core.audit.SecurityEventType.TOOL_EXECUTED;
import static com.amsha.core.audit.SecurityEventType.TOOL_FAILED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.core.config.PolicyConfig;
import com.amsha.core.governance.RequiresApprovalException;
import com.amsha.core.security.AutonomyPolicy;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S1192")
class ToolGovernanceGuardTest {

    @Mock
    CapabilityRegistry capabilityRegistry;

    @Mock
    RoleRegistry roleRegistry;

    @Mock
    AuditService auditService;

    @Mock
    AutonomyPolicy autonomyPolicy;

    @Mock
    PolicyConfig policyConfig;

    @Mock
    PolicyConfig.Governance governance;

    @org.mockito.InjectMocks
    ToolGovernanceGuard guard;

    @BeforeEach
    void setUp() {
        when(policyConfig.governance()).thenReturn(governance);
        when(governance.hitlExemptTools()).thenReturn(List.of("manage_schedule"));
        when(governance.supervisedSalvageOperations()).thenReturn(List.of("NETWORK_OUTBOUND", "EXECUTE_SHELL"));
        guard.init();
    }

    private ToolExecutionContext ctx(String role, AutonomyLevel level) {
        return new ToolExecutionContext(
                "session", "user", "chat", role, level, "trace", null, null, null, null, null, null);
    }

    // ── verifyRoleCapability ──

    @Test
    void verifyRoleCapability_allowed_doesNotThrow() {
        when(capabilityRegistry.hasCapability("coordinator", "garuda")).thenReturn(true);
        assertDoesNotThrow(() -> guard.verifyRoleCapability(ctx("coordinator", AutonomyLevel.FULL), "garuda"));
    }

    @Test
    void verifyRoleCapability_notAllowed_throwsSecurityException() {
        when(capabilityRegistry.hasCapability("coder", "dangerous_tool")).thenReturn(false);
        SecurityException ex = assertThrows(
                SecurityException.class,
                () -> guard.verifyRoleCapability(ctx("coder", AutonomyLevel.FULL), "dangerous_tool"));
        assertTrue(ex.getMessage().contains("not permitted"));
        verify(auditService).record(any(), any(), any(), eq(TOOL_BLOCKED), any());
    }

    // ── verifyAutonomy ──

    @Test
    void verifyAutonomy_trustedRoleNonSensitive_returns() {
        when(roleRegistry.requiresApprovalForTools("debugger")).thenReturn(false);
        assertDoesNotThrow(() -> guard.verifyAutonomy(
                ctx("debugger", AutonomyLevel.FULL), "some_tool", ToolOperation.WRITE_FS, Map.of()));
    }

    @Test
    void verifyAutonomy_trustedRoleSensitiveBlocked_throwsSecurityException() {
        when(roleRegistry.requiresApprovalForTools("debugger")).thenReturn(false);
        when(autonomyPolicy.isAllowed(AutonomyLevel.FULL, ToolOperation.NETWORK_OUTBOUND))
                .thenReturn(false);
        SecurityException ex = assertThrows(
                SecurityException.class,
                () -> guard.verifyAutonomy(
                        ctx("debugger", AutonomyLevel.FULL), "web_fetch", ToolOperation.NETWORK_OUTBOUND, Map.of()));
        assertTrue(ex.getMessage().contains("not permitted"));
    }

    @Test
    void verifyAutonomy_trustedRoleSensitiveAllowed_returns() {
        when(roleRegistry.requiresApprovalForTools("debugger")).thenReturn(false);
        when(autonomyPolicy.isAllowed(AutonomyLevel.FULL, ToolOperation.NETWORK_OUTBOUND))
                .thenReturn(true);
        assertDoesNotThrow(() -> guard.verifyAutonomy(
                ctx("debugger", AutonomyLevel.FULL), "web_fetch", ToolOperation.NETWORK_OUTBOUND, Map.of()));
    }

    @Test
    void verifyAutonomy_notTrustedAllowed_returns() {
        when(roleRegistry.requiresApprovalForTools("coordinator")).thenReturn(true);
        when(autonomyPolicy.isAllowed(AutonomyLevel.FULL, ToolOperation.WRITE_FS))
                .thenReturn(true);
        assertDoesNotThrow(() -> guard.verifyAutonomy(
                ctx("coordinator", AutonomyLevel.FULL), "write_file", ToolOperation.WRITE_FS, Map.of()));
    }

    @Test
    void verifyAutonomy_notTrustedBlockedHitlExemptSalvage_returns() {
        when(roleRegistry.requiresApprovalForTools("coordinator")).thenReturn(true);
        when(autonomyPolicy.isAllowed(AutonomyLevel.SUPERVISED, ToolOperation.EXECUTE_SHELL))
                .thenReturn(false);
        assertDoesNotThrow(() -> guard.verifyAutonomy(
                ctx("coordinator", AutonomyLevel.SUPERVISED),
                "manage_schedule",
                ToolOperation.EXECUTE_SHELL,
                Map.of()));
    }

    @Test
    void verifyAutonomy_notTrustedBlockedSupervisedSalvage_requiresApproval() {
        when(roleRegistry.requiresApprovalForTools("coordinator")).thenReturn(true);
        when(autonomyPolicy.isAllowed(AutonomyLevel.SUPERVISED, ToolOperation.EXECUTE_SHELL))
                .thenReturn(false);
        RequiresApprovalException ex = assertThrows(
                RequiresApprovalException.class,
                () -> guard.verifyAutonomy(
                        ctx("coordinator", AutonomyLevel.SUPERVISED),
                        "run_shell",
                        ToolOperation.EXECUTE_SHELL,
                        Map.of("x", "y")));
        assertNotNull(ex);
    }

    @Test
    void verifyAutonomy_notTrustedBlockedNotSalvage_throwsSecurityException() {
        when(roleRegistry.requiresApprovalForTools("coordinator")).thenReturn(true);
        when(autonomyPolicy.isAllowed(AutonomyLevel.FULL, ToolOperation.EXECUTE_SHELL))
                .thenReturn(false);
        SecurityException ex = assertThrows(
                SecurityException.class,
                () -> guard.verifyAutonomy(
                        ctx("coordinator", AutonomyLevel.FULL), "run_shell", ToolOperation.EXECUTE_SHELL, Map.of()));
        assertTrue(ex.getMessage().contains("not permitted"));
    }

    // ── audit recorders ──

    @Test
    void recordToolAudit_recordsExecuted() {
        guard.recordToolAudit(ctx("coordinator", AutonomyLevel.FULL), "garuda");
        verify(auditService).record(any(), any(), any(), eq(TOOL_EXECUTED), any());
    }

    @Test
    void recordToolFailureAudit_nonFailure_keepsNullDetails() {
        guard.recordToolFailureAudit(ctx("coordinator", AutonomyLevel.FULL), "garuda", ToolResponse.success("done"));
        verify(auditService).record(any(), any(), any(), eq(TOOL_FAILED), contains("errorCode=null"));
    }

    @Test
    void recordToolFailureAudit_toolFailure_recordsDetails() {
        ToolResponse.ToolFailure fail =
                new ToolResponse.ToolFailure(500, "boom", false, "ERR_1", "trace-9", "com.foo.Bar");
        guard.recordToolFailureAudit(ctx("coordinator", AutonomyLevel.FULL), "garuda", fail);
        verify(auditService)
                .record(
                        any(),
                        any(),
                        any(),
                        eq(TOOL_FAILED),
                        contains("errorCode=ERR_1 traceId=trace-9 rootCause=com.foo.Bar"));
    }
}
