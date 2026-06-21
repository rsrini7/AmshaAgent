package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.api.AgentSessionService;
import com.amsha.core.api.dto.ServiceResult;
import com.amsha.core.audit.AuditVerifier;
import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.api.AuditEventStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditResourceTest {

    @Mock
    AuditEventStorePort auditStore;

    @Mock
    AuditVerifier auditVerifier;

    @Mock
    AgentSessionService sessionService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    AuditResource resource;

    private static final String USER_ID = "test-user";
    private static final String SESSION_ID = "sess-123";

    @BeforeEach
    void setUp() {
        resource = new AuditResource();
        setField(resource, "auditStore", auditStore);
        setField(resource, "auditVerifier", auditVerifier);
        setField(resource, "sessionService", sessionService);
        setField(resource, "securityContext", securityContext);
    }

    @Test
    void listEventTypesReturns200WithAllEnumValues() {
        Response resp = resource.listEventTypes();

        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) resp.getEntity();
        var types = (List<?>) body.get("eventTypes");
        assertFalse(types.isEmpty());
    }

    @Test
    void listAuditEventsReturns200WithPaginatedResults() {
        when(auditStore.findAll(null, null, null, null, 0, 50)).thenReturn(List.of());
        when(auditStore.countAll(null, null, null, null)).thenReturn(0L);

        Response resp = resource.listAuditEvents(null, null, null, null, 0, 50);

        assertEquals(200, resp.getStatus());
    }

    @Test
    void getAuditEventReturns200WhenFound() {
        UUID id = UUID.randomUUID();
        AuditEventDto dto = sampleAuditDto(id);
        when(auditStore.findById(id)).thenReturn(Optional.of(dto));

        Response resp = resource.getAuditEvent(id.toString());

        assertEquals(200, resp.getStatus());
    }

    @Test
    void getAuditEventReturns404WhenNotFound() {
        UUID id = UUID.randomUUID();
        when(auditStore.findById(id)).thenReturn(Optional.empty());

        Response resp = resource.getAuditEvent(id.toString());

        assertEquals(404, resp.getStatus());
    }

    @Test
    void getAuditEventReturns400ForInvalidId() {
        Response resp = resource.getAuditEvent("not-a-uuid");

        assertEquals(400, resp.getStatus());
    }

    @Test
    void getSessionAuditReturns200WhenSessionValid() {
        when(sessionService.validateSession(SESSION_ID, USER_ID)).thenReturn(Optional.empty());
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(auditStore.findBySessionId(SESSION_ID)).thenReturn(List.of(sampleAuditDto(UUID.randomUUID())));

        Response resp = resource.getSessionAudit(SESSION_ID);

        assertEquals(200, resp.getStatus());
    }

    @Test
    void getSessionAuditReturns404WhenSessionInvalid() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(sessionService.validateSession(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(ServiceResult.error("SESSION_NOT_FOUND", "not found")));

        Response resp = resource.getSessionAudit(SESSION_ID);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void verifySessionChainReturns200WithResult() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(sessionService.validateSession(SESSION_ID, USER_ID)).thenReturn(Optional.empty());
        AuditVerifier.VerificationResult result = new AuditVerifier.VerificationResult(true, null, 5);
        when(auditVerifier.verifyChain(SESSION_ID)).thenReturn(result);

        Response resp = resource.verifySessionChain(SESSION_ID);

        assertEquals(200, resp.getStatus());
    }

    private AuditEventDto sampleAuditDto(UUID id) {
        return new AuditEventDto(
                id,
                SESSION_ID,
                USER_ID,
                "coordinator",
                "hash123",
                "FINISH",
                "TOOL_INVOKED",
                "detail",
                100,
                50,
                BigDecimal.ZERO,
                Instant.now(),
                1,
                "1.0.0",
                "idem-key",
                1L,
                "hmac",
                "prev");
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
