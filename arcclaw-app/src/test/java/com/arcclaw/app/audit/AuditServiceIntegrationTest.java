package com.arcclaw.app.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.util.List;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class AuditServiceIntegrationTest {

    @Inject
    AuditService auditService;

    @Inject
    AuditEventStorePort auditStore;

    @Inject
    SecretVault secretVault;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    ToolRegistry toolRegistry;

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

                List<AuditEventDto> events = auditStore.findBySessionId(sessionId);
                assertTrue(
                        events.stream()
                                .anyMatch(e ->
                                        SecurityEventType.VAULT_STORE.name().equals(e.eventType())),
                        "Expected VAULT_STORE event but got: "
                                + events.stream().map(AuditEventDto::eventType).toList());

                AuditEventDto event = events.stream()
                        .filter(e -> SecurityEventType.VAULT_STORE.name().equals(e.eventType()))
                        .findFirst()
                        .orElseThrow();
                assertTrue(event.eventDetail().contains("keyName=my-secret"));
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

                List<AuditEventDto> events = auditStore.findBySessionId(sessionId);
                assertTrue(
                        events.stream()
                                .anyMatch(e -> SecurityEventType.VAULT_RETRIEVE_MISS
                                        .name()
                                        .equals(e.eventType())),
                        "Expected VAULT_RETRIEVE_MISS event but got: "
                                + events.stream().map(AuditEventDto::eventType).toList());

                AuditEventDto event = events.stream()
                        .filter(e ->
                                SecurityEventType.VAULT_RETRIEVE_MISS.name().equals(e.eventType()))
                        .findFirst()
                        .orElseThrow();
                assertTrue(event.eventDetail().contains("keyName=non-existent-key"));
            });
        });
    }

    @Test
    public void testCheckpointCreatedTriggersAudit() {
        WorkspaceScopeHolder.run(sessionId, () -> {
            WorkspaceContext.runWithFullAutonomy(sessionId, "test", "test", "coordinator", () -> {
                secretVault.init(sessionId); // statefulAgentStore might need vault

                statefulAgentStore.save(sessionId, "coordinator", List.of(), "test goal");

                // Wait for async persistence
                auditService.waitForIdle();

                List<AuditEventDto> events = auditStore.findBySessionId(sessionId);
                assertEquals(1, events.size());

                AuditEventDto event = events.get(0);
                assertEquals(SecurityEventType.CHECKPOINT_CREATED.name(), event.eventType());
                assertEquals("coordinator", event.agentRole());
            });
        });
    }

    @Test
    public void testDirectAuditRecord() {
        WorkspaceScopeHolder.run(sessionId, () -> {
            WorkspaceContext.runWithFullAutonomy(sessionId, "test", "test", "coordinator", () -> {
                auditService.record(sessionId, "test", "coordinator", SecurityEventType.LOOP_FINISH, "direct test");

                // Wait for async persistence
                auditService.waitForIdle();

                List<AuditEventDto> events = auditStore.findBySessionId(sessionId);
                assertEquals(1, events.size(), "Direct audit record failed to persist");
            });
        });
    }
}
