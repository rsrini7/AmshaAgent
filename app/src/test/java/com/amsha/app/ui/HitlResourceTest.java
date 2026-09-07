package com.amsha.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.Map;

import com.amsha.app.ui.dto.CheckpointApprovalRequest;
import com.amsha.app.ui.dto.PageDto;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.audit.AuditService;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HitlResourceTest {

    @Mock
    Instance<HitlService> hitlServiceInstance;

    @Mock
    HitlService hitlService;

    @Mock
    AgentSseService agentSseService;

    @Mock
    SessionHeartbeatTracker heartbeatTracker;

    @Mock
    AuditService auditService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    HitlResource resource;

    private static final String USER_ID = "test-user";
    private static final String TASK_ID = "task-123";

    @BeforeEach
    void setUp() {
        resource = new HitlResource();
        setField(resource, "hitlService", hitlServiceInstance);
        setField(resource, "agentSseService", agentSseService);
        setField(resource, "heartbeatTracker", heartbeatTracker);
        setField(resource, "auditService", auditService);
        setField(resource, "securityContext", securityContext);
    }

    @Test
    void listPendingReturns200WhenAuthenticated() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(hitlServiceInstance.isUnsatisfied()).thenReturn(false);
        when(hitlServiceInstance.get()).thenReturn(hitlService);
        when(hitlService.countPendingApprovals(USER_ID)).thenReturn(0L);
        when(hitlService.getPendingApprovals(USER_ID, 20, 0)).thenReturn(Map.of());

        Response resp = resource.listPending(0, 20);

        assertEquals(200, resp.getStatus());
    }

    @Test
    void listPendingReturnsUnauthorizedWhenNoUser() {
        when(securityContext.getUserPrincipal()).thenReturn(null);

        Response resp = resource.listPending(0, 20);

        assertEquals(401, resp.getStatus());
    }

    @Test
    void listPendingReturns200WhenHitlUnavailable() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(hitlServiceInstance.isUnsatisfied()).thenReturn(true);

        Response resp = resource.listPending(0, 20);

        assertEquals(200, resp.getStatus());
        PageDto<?> page = (PageDto<?>) resp.getEntity();
        assertEquals(0, page.totalCount());
    }

    @Test
    void approveReturns200OnSuccess() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(hitlServiceInstance.isUnsatisfied()).thenReturn(false);
        when(hitlServiceInstance.get()).thenReturn(hitlService);

        CheckpointApprovalRequest request = new CheckpointApprovalRequest("sandbox_exec", "looks safe");
        Response resp = resource.approve(TASK_ID, request, null);

        assertEquals(200, resp.getStatus());
        verify(hitlService).resolve(TASK_ID, "sandbox_exec", true, "looks safe", USER_ID);
    }

    @Test
    void approveReturns501WhenHitlUnavailable() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(hitlServiceInstance.isUnsatisfied()).thenReturn(true);

        Response resp = resource.approve(TASK_ID, new CheckpointApprovalRequest("tool", "reason"), null);

        assertEquals(501, resp.getStatus());
    }

    @Test
    void approveReturns404WhenNoPendingApproval() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(hitlServiceInstance.isUnsatisfied()).thenReturn(false);
        when(hitlServiceInstance.get()).thenReturn(hitlService);
        doThrow(new IllegalArgumentException("No pending approval found"))
                .when(hitlService)
                .resolve(anyString(), anyString(), anyBoolean(), anyString(), anyString());

        Response resp = resource.approve(TASK_ID, new CheckpointApprovalRequest("tool", "reason"), null);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void approveReturns409WhenAlreadyResolved() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(hitlServiceInstance.isUnsatisfied()).thenReturn(false);
        when(hitlServiceInstance.get()).thenReturn(hitlService);
        doThrow(new IllegalStateException("already resolved"))
                .when(hitlService)
                .resolve(anyString(), anyString(), anyBoolean(), anyString(), anyString());

        Response resp = resource.approve(TASK_ID, new CheckpointApprovalRequest("tool", "reason"), null);

        assertEquals(409, resp.getStatus());
    }

    @Test
    void rejectReturns200OnSuccess() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(hitlServiceInstance.isUnsatisfied()).thenReturn(false);
        when(hitlServiceInstance.get()).thenReturn(hitlService);

        Response resp = resource.reject(TASK_ID, new CheckpointApprovalRequest("sandbox_exec", "too risky"), null);

        assertEquals(200, resp.getStatus());
        verify(hitlService).resolve(TASK_ID, "sandbox_exec", false, "too risky", USER_ID);
    }

    @Test
    void rejectReturns501WhenHitlUnavailable() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(hitlServiceInstance.isUnsatisfied()).thenReturn(true);

        Response resp = resource.reject(TASK_ID, new CheckpointApprovalRequest("tool", "reason"), null);

        assertEquals(501, resp.getStatus());
    }

    @Test
    void approveReturnsIdempotentCachedResponse() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(hitlServiceInstance.isUnsatisfied()).thenReturn(false);
        when(hitlServiceInstance.get()).thenReturn(hitlService);

        CheckpointApprovalRequest request = new CheckpointApprovalRequest("tool", "ok");
        Response first = resource.approve(TASK_ID, request, "idem-key-1");
        Response second = resource.approve(TASK_ID, request, "idem-key-1");

        assertEquals(first.getStatus(), second.getStatus());
        // resolve should only be called once due to idempotency cache
        verify(hitlService, times(1)).resolve(anyString(), anyString(), anyBoolean(), anyString(), anyString());
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
