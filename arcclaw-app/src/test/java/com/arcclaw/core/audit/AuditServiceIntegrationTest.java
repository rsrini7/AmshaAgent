package com.arcclaw.core.audit;

import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class AuditServiceIntegrationTest {

    @Inject
    AuditService auditService;

    @Inject
    AuditEventRepository repository;

    @Inject
    SecretVault secretVault;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    WorkspaceContext workspaceContext;

    @Inject
    EntityManager em;

    private String sessionId;

    @BeforeEach
    public void setup() {
        sessionId = "test-integration-" + System.currentTimeMillis();
        WorkspaceScopeHolder.set(sessionId);
        workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);
        secretVault.init(sessionId);
    }

    @AfterEach
    public void teardown() {
        WorkspaceScopeHolder.clear();
    }

    @Test
    public void testVaultStoreTriggersAudit() {
        secretVault.store("my-secret", "super-secret-value".getBytes());

        // Wait for async persistence
        auditService.waitForIdle();
        em.clear();

        List<AuditEvent> events = repository.findBySessionId(sessionId);
        assertEquals(1, events.size());
        
        AuditEvent event = events.get(0);
        assertEquals(SecurityEventType.VAULT_STORE, event.eventType);
        assertTrue(event.eventDetail.contains("keyName=my-secret"));
    }

    @Test
    public void testVaultRetrieveMissTriggersAudit() {
        secretVault.get("non-existent-key");

        // Wait for async persistence
        auditService.waitForIdle();
        em.clear();

        List<AuditEvent> events = repository.findBySessionId(sessionId);
        assertEquals(1, events.size());
        
        AuditEvent event = events.get(0);
        assertEquals(SecurityEventType.VAULT_RETRIEVE_MISS, event.eventType);
        assertTrue(event.eventDetail.contains("keyName=non-existent-key"));
    }

    @Test
    public void testCheckpointCreatedTriggersAudit() {
        statefulAgentStore.save(sessionId, "coordinator", List.of());

        // Wait for async persistence
        auditService.waitForIdle();
        em.clear();

        List<AuditEvent> events = repository.findBySessionId(sessionId);
        assertEquals(1, events.size());
        
        AuditEvent event = events.get(0);
        assertEquals(SecurityEventType.CHECKPOINT_CREATED, event.eventType);
        assertEquals("coordinator", event.agentRole);
    }
}
