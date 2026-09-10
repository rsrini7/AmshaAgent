package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.message.UserMessages;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ParallelBatchResumeHandlerTest {

    @Test
    void delegatedApprovalPauseDoesNotCompleteParentBatch() throws Exception {
        StatefulAgentStore agentStore = mock(StatefulAgentStore.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDelegationResolver delegationResolver = mock(ToolDelegationResolver.class);
        ToolInvocationGuard guard = mock(ToolInvocationGuard.class);
        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        ObjectMapper mapper = new ObjectMapper();

        ParallelToolCall call = ParallelToolCall.pending(
                "call-1",
                "garuda",
                Map.of("agent", "saraswati", "goal", "research"),
                mapper.writeValueAsString(Map.of("agent", "saraswati", "goal", "research")));
        when(checkpoint.getPendingToolParametersJson()).thenReturn(mapper.writeValueAsString(List.of(call)));
        when(checkpoint.hasBatchToolDecisions()).thenReturn(true);
        when(checkpoint.getBatchToolDecisions()).thenReturn("{\"garuda\":\"APPROVED\"}");
        when(checkpoint.hasPreExecutedResults()).thenReturn(false);
        when(checkpoint.getPendingToolRole()).thenReturn("coordinator");
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.RESUMING);
        when(delegationResolver.resolveOwner(anyString(), anyString())).thenReturn(Optional.empty());
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
        when(toolRegistry.invokeTool(anyString(), anyString(), anyString()))
                .thenReturn(ToolResponse.success(
                        UserMessages.delegationPaused("saraswati", "web_fetch", "{\"url\":\"https://example.com\"}")));

        ParallelBatchResumeHandler handler =
                new ParallelBatchResumeHandler(agentStore, toolRegistry, mapper, delegationResolver, guard);
        AgentResult result = handler.resumeParallelBatch(
                mockContext("parent-session"), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        AgentResult.AwaitingApproval awaiting = assertInstanceOf(AgentResult.AwaitingApproval.class, result);
        assertEquals("web_fetch", awaiting.toolName());
        verify(checkpoint).setStatus(CheckpointStatus.AWAITING_APPROVAL);
        verify(agentStore).save(checkpoint);
    }

    @Test
    void consumesObjectShapedPreExecutedResultWithoutReinvokingTool() throws Exception {
        StatefulAgentStore agentStore = mock(StatefulAgentStore.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDelegationResolver delegationResolver = mock(ToolDelegationResolver.class);
        ToolInvocationGuard guard = mock(ToolInvocationGuard.class);
        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        ObjectMapper mapper = new ObjectMapper();
        ParallelToolCall call = ParallelToolCall.pending("call-1", "safe", Map.of(), "{}");

        when(checkpoint.getPendingToolParametersJson()).thenReturn(mapper.writeValueAsString(List.of(call)));
        when(checkpoint.hasBatchToolDecisions()).thenReturn(true);
        when(checkpoint.getBatchToolDecisions()).thenReturn("{\"safe\":\"APPROVED\"}");
        when(checkpoint.hasPreExecutedResults()).thenReturn(true);
        when(checkpoint.getPreExecutedResults())
                .thenReturn(mapper.writerFor(new TypeReference<Map<String, ToolResponse>>() {})
                        .writeValueAsString(Map.of("call-1", ToolResponse.success("already done"))));

        ParallelBatchResumeHandler handler =
                new ParallelBatchResumeHandler(agentStore, toolRegistry, mapper, delegationResolver, guard);
        AgentResult result = handler.resumeParallelBatch(
                mockContext("parent-session"), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        assertEquals(null, result);
        verify(toolRegistry, never()).invokeTool(anyString(), anyString(), anyString());
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
    }

    private static AgentExecutionContext mockContext(String sessionId) {
        return new AgentExecutionContext(
                sessionId,
                "user-1",
                "chat-1",
                "coordinator",
                "goal",
                AutonomyLevel.SUPERVISED,
                StructuredOutputMode.NATIVE,
                5,
                0,
                "TELEGRAM",
                "chat-1",
                "",
                Map.of(),
                List.of());
    }
}
