package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.SecurityContext;

import java.time.Duration;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.localml.onnx.OnnxSessionLeaseManager;
import com.amsha.localml.onnx.OnnxSessionLeaseManager.UnloadResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnnxModelAdminResourceTest {

    private OnnxModelAdminResource resource;
    private OnnxSessionLeaseManager sessionLeaseManager;
    private AuditService auditService;
    private SecurityContext securityContext;

    @BeforeEach
    @SuppressWarnings("UnnecessaryAssignment")
    void setUp() {
        resource = new OnnxModelAdminResource();
        sessionLeaseManager = mock(OnnxSessionLeaseManager.class);
        auditService = mock(AuditService.class);
        securityContext = mock(SecurityContext.class);
        resource.sessionLeaseManager = sessionLeaseManager;
        resource.auditService = auditService;
        resource.securityContext = securityContext;
        resource.shutdownGrace = Duration.ofSeconds(3);
    }

    @Test
    void rejectsNonInternalCallerBeforeAuditOrMutation() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        assertEquals(403, resource.unload("embedding-v1").getStatus());

        verifyNoInteractions(auditService, sessionLeaseManager);
    }

    @Test
    void rejectsInvalidBundleIdBeforeAuditOrMutation() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        assertEquals(400, resource.unload("../embedding-v1").getStatus());

        verifyNoInteractions(auditService, sessionLeaseManager);
    }

    @Test
    void auditsBeforeUnloadingLoadedBundle() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(securityContext.getUserPrincipal()).thenReturn(() -> "internal-admin");
        when(sessionLeaseManager.unload("embedding-v1", Duration.ofSeconds(3))).thenReturn(UnloadResult.UNLOADED);

        var response = resource.unload("embedding-v1");

        assertEquals(200, response.getStatus());
        var order = inOrder(auditService, sessionLeaseManager);
        order.verify(auditService)
                .recordMandatory(
                        "local-ml-model:embedding-v1",
                        "internal-admin",
                        "system",
                        SecurityEventType.ONNX_MODEL_UNLOAD_REQUESTED,
                        "bundleId=embedding-v1");
        order.verify(sessionLeaseManager).unload("embedding-v1", Duration.ofSeconds(3));
    }

    @Test
    void returnsConflictWhileAcceptedLeasesDrain() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(sessionLeaseManager.unload("embedding-v1", Duration.ofSeconds(3))).thenReturn(UnloadResult.DRAINING);

        assertEquals(409, resource.unload("embedding-v1").getStatus());
    }

    @Test
    void returnsNotFoundWhenBundleIsNotLoaded() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(sessionLeaseManager.unload("embedding-v1", Duration.ofSeconds(3))).thenReturn(UnloadResult.NOT_LOADED);

        assertEquals(404, resource.unload("embedding-v1").getStatus());
    }

    @Test
    void mandatoryAuditFailurePreventsUnload() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService)
                .recordMandatory(
                        anyString(),
                        anyString(),
                        anyString(),
                        eq(SecurityEventType.ONNX_MODEL_UNLOAD_REQUESTED),
                        anyString());

        assertThrows(IllegalStateException.class, () -> resource.unload("embedding-v1"));

        verifyNoInteractions(sessionLeaseManager);
    }
}
