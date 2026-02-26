package com.arcclaw.core.lifecycle;

import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
class WorkspaceManagerGcSweepTest {

    @Inject
    WorkspaceManager workspaceManager;

    @InjectMock
    EntityManager emMock;

    @InjectMock
    StatefulAgentStore agentStoreMock; // Mock to avoid DB hits during terminateSession

    @BeforeEach
    void setup() {
        workspaceManager.clearAll();
    }

    @Test
    void testGcSweep_awaitingApprovalSession_isNotTerminated() {
        String sessionId = "session-awaiting";
        
        injectExpiredHeartbeat(sessionId);

        // Stub EntityManager.createQuery to handle different queries in the flow
        stubEntityManagerForGcSweep(sessionId, true);

        // Act
        workspaceManager.gcSweep();

        // Assert: session still exists (not terminated)
        assertEquals(1, workspaceManager.activeSessionCount());
    }

    @Test
    void testGcSweep_expiredNonAwaitingSession_isTerminated() {
        String sessionId = "session-expired";
        
        injectExpiredHeartbeat(sessionId);

        // Stub EntityManager.createQuery to return EMPTY awaitingApprovalIds
        stubEntityManagerForGcSweep(sessionId, false);

        // Act
        workspaceManager.gcSweep();

        // Assert: session is removed from heartbeats
        assertEquals(0, workspaceManager.activeSessionCount());
    }

    @Test
    void testGcSweep_bulkQuery_calledOnceFor3ExpiredSessions() {
        String s1 = "s1", s2 = "s2", s3 = "s3";
        
        injectExpiredHeartbeat(s1);
        injectExpiredHeartbeat(s2);
        injectExpiredHeartbeat(s3);

        // Stub EntityManager.createQuery for s1 awaiting approval
        stubEntityManagerForGcSweep(s1, true);

        // Act
        workspaceManager.gcSweep();

        // Assert: Bulk query executed exactly ONCE
        verify(emMock, times(1)).createQuery(contains("SELECT c.sessionId FROM AgentCheckpoint"), eq(String.class));

        // Assert: s1 survives, s2 and s3 are terminated
        assertEquals(1, workspaceManager.activeSessionCount());
    }

    /**
     * Helper to stub EntityManager.createQuery for both the bulk query and the cleanup queries.
     */
    private void stubEntityManagerForGcSweep(String awaitingId, boolean isAwaiting) {
        // Mock for the session IDs bulk query
        TypedQuery<String> bulkQueryMock = mock(TypedQuery.class, withSettings().extraInterfaces(org.hibernate.query.Query.class));
        when(bulkQueryMock.setParameter(anyString(), any())).thenReturn(bulkQueryMock);
        if (isAwaiting) {
            when(bulkQueryMock.getResultStream()).thenReturn(Stream.of(awaitingId));
        } else {
            when(bulkQueryMock.getResultStream()).thenReturn(Stream.empty());
        }

        // Mock for standard queries (like in markCheckpointsFailed)
        TypedQuery<Object> fallbackQueryMock = mock(TypedQuery.class, withSettings().extraInterfaces(org.hibernate.query.Query.class));
        when(fallbackQueryMock.setParameter(anyString(), any())).thenReturn(fallbackQueryMock);
        when(fallbackQueryMock.getResultList()).thenReturn(List.of());

        // Use doAnswer to return the right mock based on requested Class
        doAnswer(invocation -> {
            Class<?> clazz = invocation.getArgument(1);
            if (clazz == String.class) {
                return bulkQueryMock;
            }
            return fallbackQueryMock;
        }).when(emMock).createQuery(anyString(), any(Class.class));
    }

    private void injectExpiredHeartbeat(String sessionId) {
        try {
            WorkspaceManager actualManager = ClientProxy.unwrap(workspaceManager);
            Field field = WorkspaceManager.class.getDeclaredField("heartbeats");
            field.setAccessible(true);
            java.util.concurrent.ConcurrentHashMap<String, Instant> heartbeats = 
                (java.util.concurrent.ConcurrentHashMap<String, Instant>) field.get(actualManager);
            
            Instant expired = Instant.now().minus(java.time.Duration.ofMinutes(60));
            heartbeats.put(sessionId, expired);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
