package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.message.UserMessages;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.RiskLevel;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class DelegatedCompletionHandoffTest {

    /** The child session id shape produced by the delegation path: {@code <parent>:delegate:<agent>:<n>}. */
    private static final String CHILD = "parent:delegate:saraswati:123";

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

    // ================= capture(): inputs it must refuse =================

    @Test
    void capture_returnsEmptyForANonTerminalChildResultOrAMissingChildSession() {
        DelegatedCompletionHandoff handoff = new DelegatedCompletionHandoff(mock(StatefulAgentStore.class), mapper());
        AgentResult awaiting = AgentResult.awaitingApproval(
                new ApprovalFrame("garuda", Map.of(), RiskLevel.MEDIUM), "parent", List.of());

        // A child that is still awaiting approval has no final answer to hand back yet.
        assertTrue(handoff.capture(CHILD, awaiting).isEmpty());
        // ...and a null child session cannot be attributed to any parent.
        assertTrue(
                handoff.capture(null, AgentResult.finished("done", List.of())).isEmpty());
    }

    @Test
    void capture_returnsEmptyWhenNoActiveParentCheckpointExists() {
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        when(store.findLatestAwaitingApproval("parent")).thenReturn(Optional.empty());
        when(store.findLatestCheckpoint("parent")).thenReturn(Optional.empty());

        assertTrue(new DelegatedCompletionHandoff(store, mapper())
                .capture(CHILD, AgentResult.finished("done", List.of()))
                .isEmpty());
    }

    @Test
    void capture_returnsEmptyForASequentialParentWhosePendingToolIsNotADelegation() {
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        AgentCheckpoint parent = new AgentCheckpointRecord();
        parent.setSessionId("parent");
        parent.setAgentRole("coordinator");
        parent.setStatus(CheckpointStatus.IN_PROGRESS);
        parent.setPendingToolName("web_fetch");
        when(store.findLatestCheckpoint("parent")).thenReturn(Optional.of(parent));

        // The parent is not waiting on a delegation, so the child result is not its to consume.
        assertTrue(new DelegatedCompletionHandoff(store, mapper())
                .capture(CHILD, AgentResult.finished("done", List.of()))
                .isEmpty());
    }

    // ================= capture(): matching a batch call =================

    @Test
    void capture_returnsEmptyWhenNoParentBatchCallMatchesTheChild() {
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        // Neither call names the child agent: the first has no params at all, the second has
        // params without an agent/persona/target key.
        List<ParallelToolCall> calls = List.of(
                new ParallelToolCall("call-1", "garuda", null, "{}", null, false),
                ParallelToolCall.pending("call-2", "garuda", Map.of("x", "y"), "{}"));
        AgentCheckpoint parent = parallelParent(calls);
        when(store.findLatestAwaitingApproval("parent")).thenReturn(Optional.of(parent));

        assertTrue(new DelegatedCompletionHandoff(store, mapper())
                .capture(CHILD, AgentResult.finished("done", List.of()))
                .isEmpty());
    }

    @Test
    void capture_fallsBackToTheOnlyCallWhenItsParamsDoNotNameTheChild() throws Exception {
        ObjectMapper mapper = mapper();
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        // A single-call batch is unambiguous even when the params do not name the agent.
        List<ParallelToolCall> calls = List.of(ParallelToolCall.pending("call-1", "garuda", Map.of("x", "y"), "{}"));
        AgentCheckpoint parent = parallelParent(calls);
        when(store.findLatestAwaitingApproval("parent")).thenReturn(Optional.of(parent));

        var handoff = new DelegatedCompletionHandoff(store, mapper)
                .capture(CHILD, AgentResult.finished("research complete", List.of()));

        assertTrue(handoff.isPresent());
        assertEquals(
                "research complete",
                savedResults(mapper, parent).get("call-1").path("output").asText());
    }

    @Test
    void capture_returnsEmptyWhenTheOnlyCallIsAlreadyResolved() {
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        List<ParallelToolCall> calls =
                List.of(ParallelToolCall.pending("call-1", "garuda", Map.of("agent", "saraswati"), "{}"));
        AgentCheckpoint parent = parallelParent(calls);
        parent.setPreExecutedResults("{\"call-1\":{\"output\":\"already done\"}}");
        when(store.findLatestAwaitingApproval("parent")).thenReturn(Optional.of(parent));

        // Replaying a child whose result is already recorded would double-count the delegation.
        assertTrue(new DelegatedCompletionHandoff(store, mapper())
                .capture(CHILD, AgentResult.finished("done", List.of()))
                .isEmpty());
    }

    // ================= capture(): persistence and readiness =================

    @Test
    void capture_returnsEmptyWhenTheChildResultCannotBePersisted() {
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        List<ParallelToolCall> calls =
                List.of(ParallelToolCall.pending("call-1", "garuda", Map.of("agent", "saraswati"), "{}"));
        AgentCheckpoint parent = parallelParent(calls);
        when(store.findLatestAwaitingApproval("parent")).thenReturn(Optional.of(parent));
        doThrow(new IllegalStateException("db down")).when(store).save(any(AgentCheckpoint.class));

        // A failed write must degrade to "not captured", never to a half-updated parent.
        assertTrue(new DelegatedCompletionHandoff(store, mapper())
                .capture(CHILD, AgentResult.finished("done", List.of()))
                .isEmpty());
    }

    @Test
    void capture_marksTheParentNotReadyWhileASiblingDelegationIsStillActive() {
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        List<ParallelToolCall> calls =
                List.of(ParallelToolCall.pending("call-1", "garuda", Map.of("agent", "saraswati"), "{}"));
        AgentCheckpoint parent = parallelParent(calls);
        when(store.findLatestAwaitingApproval("parent")).thenReturn(Optional.of(parent));

        AgentCheckpoint unrelated = new AgentCheckpointRecord();
        unrelated.setSessionId("some-other-session");
        AgentCheckpoint sibling = new AgentCheckpointRecord();
        sibling.setSessionId("parent:delegate:brihaspati:9");
        when(store.findByStatus(any())).thenReturn(List.of(unrelated, sibling));

        var handoff =
                new DelegatedCompletionHandoff(store, mapper()).capture(CHILD, AgentResult.finished("done", List.of()));

        assertTrue(handoff.isPresent());
        // A sibling child is still in flight, so the parent must not be resumed yet.
        assertFalse(handoff.get().ready());
    }

    @Test
    void capture_mapsAStructuredChildResultToAToolSuccess() throws Exception {
        ObjectMapper mapper = mapper();
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        List<ParallelToolCall> calls =
                List.of(ParallelToolCall.pending("call-1", "garuda", Map.of("agent", "saraswati"), "{}"));
        AgentCheckpoint parent = parallelParent(calls);
        when(store.findLatestAwaitingApproval("parent")).thenReturn(Optional.of(parent));

        new DelegatedCompletionHandoff(store, mapper)
                .capture(CHILD, AgentResult.structuredFinished("{\"answer\":42}", Map.of("answer", 42), List.of()));

        assertEquals(
                "{\"answer\":42}",
                savedResults(mapper, parent).get("call-1").path("output").asText());
    }

    @Test
    void capture_mapsAFailedChildResultToAToolFailure() throws Exception {
        ObjectMapper mapper = mapper();
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        List<ParallelToolCall> calls =
                List.of(ParallelToolCall.pending("call-1", "garuda", Map.of("agent", "saraswati"), "{}"));
        AgentCheckpoint parent = parallelParent(calls);
        when(store.findLatestAwaitingApproval("parent")).thenReturn(Optional.of(parent));

        new DelegatedCompletionHandoff(store, mapper)
                .capture(
                        CHILD,
                        AgentResult.failed("sub-agent exploded", AgentResult.FailureCategory.SYSTEM_ERROR, List.of()));

        JsonNode stored = savedResults(mapper, parent).get("call-1");
        assertTrue(stored.path("output").asText().contains("Sub-agent failed: sub-agent exploded"));
    }

    @Test
    void capture_handlesASequentialParentWithNoStoredHistory() {
        StatefulAgentStore store = mock(StatefulAgentStore.class);
        AgentCheckpoint parent = new AgentCheckpointRecord();
        parent.setSessionId("parent");
        parent.setAgentRole("coordinator");
        parent.setStatus(CheckpointStatus.IN_PROGRESS);
        parent.setPendingToolName("garuda");
        parent.setChannelPlatformId("operator");
        parent.setChannelType("TELEGRAM");
        when(store.findLatestCheckpoint("parent")).thenReturn(Optional.of(parent));
        when(store.deserializeSteps(parent)).thenReturn(null);

        var handoff = new DelegatedCompletionHandoff(store, mapper())
                .capture(CHILD, AgentResult.finished("research result", List.of()));

        // A checkpoint with no readable history is still resumable: start a fresh step list.
        assertTrue(handoff.isPresent());
        assertTrue(handoff.get().ready());
        verify(store).saveWithSteps(any(AgentCheckpoint.class), any());
    }

    // ================= private helpers, exercised directly =================

    @Test
    void readCalls_returnsNoCallsForABlankPayload() throws Exception {
        DelegatedCompletionHandoff handoff = new DelegatedCompletionHandoff(mock(StatefulAgentStore.class), mapper());

        assertTrue(readCalls(handoff, null).isEmpty());
        assertTrue(readCalls(handoff, "   ").isEmpty());
    }

    @Test
    void readCalls_returnsNoCallsForAnUnparseablePayload() throws Exception {
        DelegatedCompletionHandoff handoff = new DelegatedCompletionHandoff(mock(StatefulAgentStore.class), mapper());

        assertTrue(readCalls(handoff, "{not json").isEmpty());
    }

    @Test
    void readResults_parsesAPreExecutedResultMap() throws Exception {
        DelegatedCompletionHandoff handoff = new DelegatedCompletionHandoff(mock(StatefulAgentStore.class), mapper());

        Map<String, JsonNode> parsed = readResults(handoff, "{\"call-1\":{\"output\":\"done\"}}");

        assertEquals(1, parsed.size());
        assertEquals("done", parsed.get("call-1").path("output").asText());
    }

    @Test
    void readResults_returnsAnEmptyMapForAnUnparseableOrNullPayload() throws Exception {
        DelegatedCompletionHandoff handoff = new DelegatedCompletionHandoff(mock(StatefulAgentStore.class), mapper());

        assertTrue(readResults(handoff, "{not json").isEmpty());
        // A column holding the literal JSON `null` parses to a null map, not to an exception.
        assertTrue(readResults(handoff, "null").isEmpty());
    }

    @Test
    void parentResume_prefersUserIdAndChatIdFromTheContextData() throws Exception {
        AgentCheckpoint parent = resumeParent("{\"userId\":\"u9\",\"chatId\":\"c9\"}");

        var resume = parentResume(parent, true);

        assertEquals("parent", resume.sessionId());
        assertEquals("coordinator", resume.agentRole());
        assertEquals("u9", resume.userId());
        assertEquals("c9", resume.chatId());
        assertEquals("TELEGRAM", resume.channelType());
        assertTrue(resume.ready());
    }

    @Test
    void parentResume_fallsBackToTheChannelPlatformIdWhenTheContextDataIsUnusable() throws Exception {
        // Corrupt JSON is swallowed; the channel coordinates remain the safe default.
        var corrupt = parentResume(resumeParent("{not json"), false);
        assertEquals("operator", corrupt.userId());
        assertEquals("operator", corrupt.chatId());
        assertFalse(corrupt.ready());

        // A literal JSON `null` parses cleanly to a null map, which must be skipped rather than dereferenced.
        var nulled = parentResume(resumeParent("null"), true);
        assertEquals("operator", nulled.userId());
        assertEquals("operator", nulled.chatId());
    }

    // ================= fixtures =================

    private static ObjectMapper mapper() {
        return new ObjectMapper();
    }

    private static AgentCheckpoint parallelParent(List<ParallelToolCall> calls) {
        AgentCheckpoint parent = new AgentCheckpointRecord();
        parent.setSessionId("parent");
        parent.setAgentRole("coordinator");
        parent.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        parent.setPendingToolName("parallel|garuda");
        try {
            parent.setPendingToolParametersJson(mapper().writeValueAsString(calls));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        parent.setChannelPlatformId("operator");
        parent.setChannelType("TELEGRAM");
        return parent;
    }

    private static AgentCheckpoint resumeParent(String contextDataJson) {
        AgentCheckpoint parent = new AgentCheckpointRecord();
        parent.setSessionId("parent");
        parent.setAgentRole("coordinator");
        parent.setChannelPlatformId("operator");
        parent.setChannelType("TELEGRAM");
        parent.setContextDataJson(contextDataJson);
        return parent;
    }

    private static Map<String, JsonNode> savedResults(ObjectMapper mapper, AgentCheckpoint parent) throws Exception {
        return mapper.readValue(
                parent.getPreExecutedResults(),
                mapper.getTypeFactory().constructMapType(Map.class, String.class, JsonNode.class));
    }

    @SuppressWarnings("unchecked")
    private static List<ParallelToolCall> readCalls(DelegatedCompletionHandoff handoff, String json) throws Exception {
        var method = DelegatedCompletionHandoff.class.getDeclaredMethod("readCalls", String.class);
        method.setAccessible(true);
        return (List<ParallelToolCall>) method.invoke(handoff, json);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, JsonNode> readResults(DelegatedCompletionHandoff handoff, String json) throws Exception {
        var method = DelegatedCompletionHandoff.class.getDeclaredMethod("readResults", String.class);
        method.setAccessible(true);
        return (Map<String, JsonNode>) method.invoke(handoff, json);
    }

    private static DelegatedCompletionHandoff.ParentResume parentResume(AgentCheckpoint parent, boolean ready)
            throws Exception {
        var handoff = new DelegatedCompletionHandoff(mock(StatefulAgentStore.class), mapper());
        var method = DelegatedCompletionHandoff.class.getDeclaredMethod(
                "parentResume", AgentCheckpoint.class, boolean.class);
        method.setAccessible(true);
        return (DelegatedCompletionHandoff.ParentResume) method.invoke(handoff, parent, ready);
    }
}
