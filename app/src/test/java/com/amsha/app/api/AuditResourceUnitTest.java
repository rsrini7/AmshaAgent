package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.api.AgentSessionService;
import com.amsha.core.audit.AuditVerifier;
import com.amsha.persistence.api.AuditEventStorePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditResourceUnitTest {

    @Mock
    AuditEventStorePort auditStore;

    @Mock
    AuditVerifier auditVerifier;

    @Mock
    AgentSessionService sessionService;

    @InjectMocks
    AuditResource resource;

    @Test
    void testListEventTypes() {
        Response response = resource.listEventTypes();
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertTrue(map.containsKey("eventTypes"));
    }

    @Test
    void testListAuditEvents() {
        when(auditStore.findAll(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(auditStore.countAll(any(), any(), any(), any())).thenReturn(0L);

        Response response = resource.listAuditEvents(null, null, null, null, 0, 50);
        assertEquals(200, response.getStatus());
    }

    @Test
    void testGetAuditEventNotFound() {
        String id = UUID.randomUUID().toString();
        when(auditStore.findById(UUID.fromString(id))).thenReturn(Optional.empty());

        Response response = resource.getAuditEvent(id);
        assertEquals(404, response.getStatus());
    }
}
