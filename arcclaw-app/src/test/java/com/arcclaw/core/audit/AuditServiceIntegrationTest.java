package com.arcclaw.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

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
    EntityManager em;

    private String sessionId;

    @BeforeEach
    public void setup() {
        sessionId = "test-integration-" + System.currentTimeMillis();
    }

    @AfterEach
    public void teardown() {
        // No-op
    }

    @Test
    public void testVaultStoreTriggersAudit() {
        WorkspaceScopeHolder.run(sessionId, () -> {
            WorkspaceContext.runWithFullAutonomy(sessionId, "test", "test", "coordinator", () -> {
            secretVault.init(sessionId);
            
            secretVault.store("my-secret", "super-secret-value".getBytes());

            // Wait for async persistence
            auditService.waitForIdle();
            em.clear();

            List<AuditEvent> events = repository.findBySessionId(sessionId);
            assertEquals(1, events.size());
            
            AuditEvent event = events.get(0);
            assertEquals(SecurityEventType.VAULT_STORE, event.eventType);
            assertTrue(event.eventDetail.contains("keyName=my-secret"));
            });
        });
    }

    @Test
    public void testVaultRetrieveMissTriggersAudit() {
        WorkspaceScopeHolder.run(sessionId, () -> {
            WorkspaceContext.runWithFullAutonomy(sessionId, "test", "test", "coordinator", () -> {
            secretVault.init(sessionId);
            
            secretVault.get("non-existent-key");

            // Wait for async persistence
            auditService.waitForIdle();
            em.clear();

            List<AuditEvent> events = repository.findBySessionId(sessionId);
            assertEquals(1, events.size());
            
            AuditEvent event = events.get(0);
            assertEquals(SecurityEventType.VAULT_RETRIEVE_MISS, event.eventType);
            assertTrue(event.eventDetail.contains("keyName=non-existent-key"));
            });
        });
    }

    @Test
    public void testCheckpointCreatedTriggersAudit() {
        WorkspaceScopeHolder.run(sessionId, () -> {
            WorkspaceContext.runWithFullAutonomy(sessionId, "test", "test", "coordinator", () -> {
            secretVault.init(sessionId); // statefulAgentStore might need vault

            statefulAgentStore.save(sessionId, "coordinator", List.of());

            // Wait for async persistence
            auditService.waitForIdle();
            em.clear();

            List<AuditEvent> events = repository.findBySessionId(sessionId);
            assertEquals(1, events.size());
            
            AuditEvent event = events.get(0);
            assertEquals(SecurityEventType.CHECKPOINT_CREATED, event.eventType);
            assertEquals("coordinator", event.agentRole);
            });
        });
    }
}
