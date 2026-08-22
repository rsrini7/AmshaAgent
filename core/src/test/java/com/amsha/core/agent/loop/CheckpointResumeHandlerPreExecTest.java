package com.amsha.core.agent.loop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.ToolResponse;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.persistence.api.ToolDecision;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckpointResumeHandlerPreExecTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    ToolRegistry toolRegistry;

    @Mock
    AgentStateMachineRunner stateMachineRunner;

    @Mock
    CronStorePort cronStore;

    @Mock
    ReasoningEngine reasoningEngine;

    ObjectMapper objectMapper = new ObjectMapper();

    CheckpointResumeHandler handler;

    @BeforeEach
    void setup() {
        handler = new CheckpointResumeHandler(
                agentStore, toolRegistry, objectMapper, null, reasoningEngine, null, null, null, null, null);
    }

    @Test
    void resumeWithPreExecutedResultsPopulated() throws Exception {
        String sessionId = "123-456";
        String agentRole = "COORDINATOR";

        AgentExecutionContext context = new AgentExecutionContext(
                sessionId,
                "user1",
                "chat1",
                agentRole,
                "goal",
                AutonomyLevel.SUPERVISED,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);

        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId(sessionId);
        cp.setAgentRole(agentRole);
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName("parallel|safe,unsafe");

        var calls = List.of(
                new ParallelToolCall("c1", "safe", Map.of(), "{}", null, true),
                new ParallelToolCall("c2", "unsafe", Map.of(), "{}", null, true));
        cp.setPendingToolParametersJson(objectMapper.writeValueAsString(calls));

        // Mock pre-executed result for 'safe'
        Map<String, String> preResults = Map.of(
                "safe",
                objectMapper.writerFor(ToolResponse.class).writeValueAsString(ToolResponse.success("pre-exec-output")));
        cp.setPreExecutedResults(objectMapper.writeValueAsString(preResults));

        // Mock 'unsafe' decision as APPROVED
        Map<String, String> decisions = Map.of("unsafe", ToolDecision.APPROVED.name());
        cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));

        when(agentStore.load(sessionId, agentRole)).thenReturn(Optional.of(cp));

        // Mock actual execution of 'unsafe'
        when(toolRegistry.invokeTool(eq("unsafe"), any(), eq(sessionId)))
                .thenReturn(ToolResponse.success("unsafe-output"));

        ChannelUser user = new ChannelUser("user1", "chat1", "TestUser", ChannelType.TELEGRAM);
        ChannelMessage message = new ChannelMessage.TelegramMessage(user, "/approve", "chat1", Instant.now());
        MessageContext msgContext = new MessageContext("chat1", user, message);
        msgContext.setSessionId(sessionId);
        msgContext.setResumeSignal(true);

        WorkspaceContext.runWithFullAutonomy(sessionId, "user1", "chat1", agentRole, () -> {
            handler.handle(context, new ArrayList<>(), msgContext, stateMachineRunner);
            return null;
        });

        // Verify toolRegistry.invokeTool was ONLY called for 'unsafe', NOT for 'safe'
        verify(toolRegistry, never()).invokeTool(eq("safe"), any(), any());
        verify(toolRegistry, times(1)).invokeTool(eq("unsafe"), any(), any());
    }
}
