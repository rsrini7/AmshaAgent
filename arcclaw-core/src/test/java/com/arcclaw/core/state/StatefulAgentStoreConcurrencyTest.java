package com.arcclaw.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.Map;

import jakarta.persistence.EntityManager;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.persistence.SqliteRetryTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatefulAgentStoreConcurrencyTest {

    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @Mock EntityManager em;
    @Mock AuditService auditService;
    @Mock SqliteRetryTemplate retryTemplate;

    @InjectMocks
    StatefulAgentStoreImpl store;

    @BeforeEach
    void setup() {
        // Mock self-injection for @Transactional proxy transparency
        store.self = store;
    }

    @Test
    void doSaveEntity_mergesStaleBatchDecisions() throws Exception {
        Long id = 100L;
        AgentCheckpoint managed = new AgentCheckpoint();
        managed.setId(id);
        managed.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        // Managed has one APPROVED, one PENDING
        Map<String, ToolDecision> managedDecisions = Map.of(
            "tool1", ToolDecision.APPROVED,
            "tool2", ToolDecision.PENDING
        );
        managed.setBatchToolDecisions(objectMapper.writeValueAsString(managedDecisions));

        AgentCheckpoint incoming = new AgentCheckpoint();
        incoming.setId(id);
        incoming.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        // Incoming is stale: both still PENDING
        Map<String, ToolDecision> incomingDecisions = Map.of(
            "tool1", ToolDecision.PENDING,
            "tool2", ToolDecision.PENDING
        );
        incoming.setBatchToolDecisions(objectMapper.writeValueAsString(incomingDecisions));

        when(em.find(AgentCheckpoint.class, id)).thenReturn(managed);

        // Act
        store.doSaveEntity(incoming);

        // Verify tool1 remains APPROVED (from managed), tool2 remains PENDING
        Map<String, ToolDecision> result = objectMapper.readValue(
            managed.getBatchToolDecisions(),
            new TypeReference<Map<String, ToolDecision>>() {});
        
        assertEquals(ToolDecision.APPROVED, result.get("tool1"));
        assertEquals(ToolDecision.PENDING, result.get("tool2"));
    }

    @Test
    void doSaveEntity_preservesIncomingFullUpdate() throws Exception {
        Long id = 200L;
        AgentCheckpoint managed = new AgentCheckpoint();
        managed.setId(id);
        managed.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        Map<String, ToolDecision> managedDecisions = Map.of(
            "tool1", ToolDecision.PENDING
        );
        managed.setBatchToolDecisions(objectMapper.writeValueAsString(managedDecisions));

        AgentCheckpoint incoming = new AgentCheckpoint();
        incoming.setId(id);
        incoming.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        // Incoming has a NEW decision
        Map<String, ToolDecision> incomingDecisions = Map.of(
            "tool1", ToolDecision.DENIED
        );
        incoming.setBatchToolDecisions(objectMapper.writeValueAsString(incomingDecisions));

        when(em.find(AgentCheckpoint.class, id)).thenReturn(managed);

        // Act
        store.doSaveEntity(incoming);

        // Verify DENIED is kept (it was a progress from PENDING)
        Map<String, ToolDecision> result = objectMapper.readValue(
            managed.getBatchToolDecisions(),
            new TypeReference<Map<String, ToolDecision>>() {});
        
        assertEquals(ToolDecision.DENIED, result.get("tool1"));
    }
}
