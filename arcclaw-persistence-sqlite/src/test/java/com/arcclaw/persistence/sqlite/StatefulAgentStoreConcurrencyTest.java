package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.common.entity.AgentCheckpointEntity;
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
    @Mock SqliteRetryTemplate retryTemplate;
    @Mock Query nativeQuery;

    @InjectMocks
    SqliteCheckpointStoreAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        // Mock native query to avoid NPE when nativeUpdate() is called
        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenReturn(1);
        setField(adapter, "self", adapter);
        
        CheckpointRepository checkpointRepo = new CheckpointRepository(em);
        ReasoningStepRepository stepRepo = new ReasoningStepRepository(em);
        CheckpointBatchOperations batchOps = new CheckpointBatchOperations(em, objectMapper);
        CheckpointUpsertService upsertService = new CheckpointUpsertService(checkpointRepo, stepRepo, batchOps);

        setField(adapter, "checkpointRepo", checkpointRepo);
        setField(adapter, "batchOps", batchOps);
        setField(adapter, "upsertService", upsertService);
        setField(adapter, "retryTemplate", retryTemplate);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void doSaveEntity_mergesStaleBatchDecisions() throws Exception {
        Long id = 100L;
        AgentCheckpoint managed = new AgentCheckpointEntity();
        managed.setId(id);
        managed.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        // Managed has one APPROVED, one PENDING
        Map<String, ToolDecision> managedDecisions = Map.of(
            "tool1", ToolDecision.APPROVED,
            "tool2", ToolDecision.PENDING
        );
        managed.setBatchToolDecisions(objectMapper.writeValueAsString(managedDecisions));

        // Incoming is stale: both still PENDING
        Map<String, ToolDecision> incomingDecisions = Map.of(
            "tool1", ToolDecision.PENDING,
            "tool2", ToolDecision.PENDING
        );
        
        CheckpointDto incoming = new CheckpointDto(
            id, "s1", "role", "AWAITING_APPROVAL", null, null, null, null, null,
            null, null, null, null, objectMapper.writeValueAsString(incomingDecisions),
            null, null, null, 0, null, null, null, null, null, null, null, 0, Instant.now(), Instant.now()
        );

        when(em.find(AgentCheckpointEntity.class, id)).thenReturn((AgentCheckpointEntity) managed);

        // Act
        adapter.doSaveEntity(incoming);

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
        AgentCheckpoint managed = new AgentCheckpointEntity();
        managed.setId(id);
        managed.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        Map<String, ToolDecision> managedDecisions = Map.of(
            "tool1", ToolDecision.PENDING
        );
        managed.setBatchToolDecisions(objectMapper.writeValueAsString(managedDecisions));

        // Incoming has a NEW decision
        Map<String, ToolDecision> incomingDecisions = Map.of(
            "tool1", ToolDecision.DENIED
        );

        CheckpointDto incoming = new CheckpointDto(
            id, "s1", "role", "AWAITING_APPROVAL", null, null, null, null, null,
            null, null, null, null, objectMapper.writeValueAsString(incomingDecisions),
            null, null, null, 0, null, null, null, null, null, null, null, 0, Instant.now(), Instant.now()
        );

        when(em.find(AgentCheckpointEntity.class, id)).thenReturn((AgentCheckpointEntity) managed);

        // Act
        adapter.doSaveEntity(incoming);

        // Verify DENIED is kept (it was a progress from PENDING)
        Map<String, ToolDecision> result = objectMapper.readValue(
            managed.getBatchToolDecisions(),
            new TypeReference<Map<String, ToolDecision>>() {});
        
        assertEquals(ToolDecision.DENIED, result.get("tool1"));
    }
}
