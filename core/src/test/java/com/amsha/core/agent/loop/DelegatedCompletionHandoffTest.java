package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.message.UserMessages;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class DelegatedCompletionHandoffTest {

    @Test
    void capturesChildResultForMatchingParentBatchCall() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        AgentCheckpoint parent = new AgentCheckpointRecord();
        parent.setSessionId("parent");
        parent.setAgentRole("coordinator");
        parent.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        parent.setPendingToolName("parallel|garuda");
        parent.setPendingToolParametersJson(mapper.writeValueAsString(List.of(ParallelToolCall.pending(
                "call-1", "garuda", Map.of("agent", "saraswati", "goal", "research"), "{\"agent\":\"saraswati\"}"))));
        parent.setChannelPlatformId("operator");
        parent.setChannelType("TELEGRAM");
        when(store.findLatestAwaitingApproval("parent")).thenReturn(Optional.of(parent));

        AgentResult result = AgentResult.finished("research complete", List.of());
        var handoff = new DelegatedCompletionHandoff(store, mapper).capture("parent:delegate:saraswati:123", result);

        assertTrue(handoff.isPresent());
        assertEquals("parent", handoff.get().sessionId());
        Map<String, JsonNode> saved = mapper.readValue(
                parent.getPreExecutedResults(),
                mapper.getTypeFactory().constructMapType(Map.class, String.class, JsonNode.class));
        assertTrue(saved.containsKey("call-1"));
        assertEquals("research complete", saved.get("call-1").path("output").asText());
        verify(store).save(any(AgentCheckpoint.class));
    }

    @Test
    void capturesChildResultForSequentialParentAndClearsPendingDelegation() {
        ObjectMapper mapper = new ObjectMapper();
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        AgentCheckpoint parent = new AgentCheckpointRecord();
        parent.setSessionId("parent");
        parent.setAgentRole("coordinator");
        parent.setStatus(CheckpointStatus.IN_PROGRESS);
        parent.setPendingToolName("garuda");
        parent.setPendingToolParametersJson("{\"agent\":\"saraswati\"}");
        parent.setChannelPlatformId("operator");
        parent.setChannelType("TELEGRAM");

        when(store.findLatestAwaitingApproval("parent")).thenReturn(Optional.empty());
        when(store.findLatestCheckpoint("parent")).thenReturn(Optional.of(parent));
        when(store.deserializeSteps(parent)).thenReturn(List.of(ReasoningStep.systemMessage("delegation paused")));

        Optional<DelegatedCompletionHandoff.ParentResume> handoff = new DelegatedCompletionHandoff(store, mapper)
                .capture("parent:delegate:saraswati:123", AgentResult.finished("research result", List.of()));

        assertTrue(handoff.isPresent());
        assertTrue(handoff.get().ready());
        assertNull(parent.getPendingToolName());
        assertNull(parent.getPendingToolParametersJson());
        assertEquals(CheckpointStatus.AWAITING_APPROVAL, parent.getStatus());
        verify(store).saveWithSteps(any(AgentCheckpoint.class), any());
    }

    @Test
    void excludesEveryPausedDelegationFromPreExecutedResults() {
        String paused = UserMessages.delegationPaused("saraswati", "web_fetch", "{\"url\":\"https://example.com\"}");
        ParallelToolCall first = ParallelToolCall.pending("call-1", "garuda", Map.of("agent", "saraswati"), "{}")
                .withResult(paused, true);
        ParallelToolCall second = ParallelToolCall.pending("call-2", "garuda", Map.of("agent", "saraswati"), "{}")
                .withResult(paused, true);
        ParallelToolCall completed =
                ParallelToolCall.pending("call-3", "safe_read", Map.of(), "{}").withResult("ok", true);

        var pause = DelegatedParallelPauseSupport.find(List.of(first, second), new ObjectMapper())
                .orElseThrow();
        Map<String, ToolResponse> results =
                DelegatedParallelPauseSupport.completedResults(List.of(), List.of(first, second, completed), pause);

        assertEquals(Map.of("call-3", ToolResponse.success("ok")), results);
    }
}
