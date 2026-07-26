package com.amsha.core.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.core.audit.AuditService;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.reflex.ReflexRegistry;
import com.amsha.core.security.AutonomyLevel;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionCleanupServiceTest {

    private SessionHeartbeatTracker heartbeatTracker;
    private AuditService auditService;
    private SessionCleanupService service;

    @BeforeEach
    void setUp() {
        heartbeatTracker = mock(SessionHeartbeatTracker.class);
        auditService = mock(AuditService.class);
        service = new SessionCleanupService(
                Logger.getLogger(SessionCleanupServiceTest.class),
                heartbeatTracker,
                mock(ReflexRegistry.class),
                auditService,
                "owner");
    }

    @Test
    void validatesRolesAndSessions() {
        assertTrue(service.validateRole("coder").isEmpty());
        assertEquals("UNKNOWN_ROLE", service.validateRole("missing-role").orElseThrow().errorCode());

        assertEquals("SESSION_NOT_FOUND", service.validateSession("missing").orElseThrow().errorCode());
        when(heartbeatTracker.exists("owned")).thenReturn(true);
        when(heartbeatTracker.getOwner("owned")).thenReturn(java.util.Optional.of("owner"));
        assertTrue(service.validateSession("owned").isEmpty());

        when(heartbeatTracker.exists("foreign")).thenReturn(true);
        when(heartbeatTracker.getOwner("foreign")).thenReturn(java.util.Optional.of("someone-else"));
        assertEquals("SESSION_ACCESS_DENIED", service.validateSession("foreign").orElseThrow().errorCode());
        verify(auditService).recordMandatory(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void createsRestContextAndDefaultsAutonomy() {
        var context = service.createRestContext("abc", "goal");
        assertAll(
                () -> assertEquals("rest-chat-abc", context.getChatId()),
                () -> assertInstanceOf(ChannelMessage.RestApiMessage.class, context.getMessage()),
                () -> assertEquals("goal", context.getMessage().text()));

        assertEquals(AutonomyLevel.SUPERVISED, service.getOrDefaultAutonomy("abc"));
        when(heartbeatTracker.getAutonomyLevel("abc")).thenReturn(AutonomyLevel.FULL);
        assertEquals(AutonomyLevel.FULL, service.getOrDefaultAutonomy("abc"));
    }
}
