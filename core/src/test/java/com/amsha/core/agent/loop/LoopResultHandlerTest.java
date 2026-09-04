package com.amsha.core.agent.loop;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

class LoopResultHandlerTest {

    @Test
    void updateStoreForTerminalPersistsFinishedAnswerToChatMemory() {
        StatefulAgentStore agentStore = mock(StatefulAgentStore.class);
        AgentSseService sseService = mock(AgentSseService.class);
        ChannelPluginRegistry channelRegistry = mock(ChannelPluginRegistry.class);
        IntentClassificationService intentClassifier = mock(IntentClassificationService.class);
        ChatMemoryWriter writer = mock(ChatMemoryWriter.class);
        LoopResultHandler handler =
                new LoopResultHandler(agentStore, sseService, channelRegistry, intentClassifier, writer);

        handler.updateStoreForTerminal(
                "s1", "coordinator", "u1", AutonomyLevel.FULL, AgentResult.finished("answer", List.of()), "goal");

        verify(writer).saveAnswer("s1", "answer");
    }

    @Test
    void updateStoreForTerminalPreservesFailedStatusWhenCheckpointWasFailed() {
        StatefulAgentStore agentStore = mock(StatefulAgentStore.class);
        AgentSseService sseService = mock(AgentSseService.class);
        ChannelPluginRegistry channelRegistry = mock(ChannelPluginRegistry.class);
        IntentClassificationService intentClassifier = mock(IntentClassificationService.class);
        ChatMemoryWriter writer = mock(ChatMemoryWriter.class);
        LoopResultHandler handler =
                new LoopResultHandler(agentStore, sseService, channelRegistry, intentClassifier, writer);

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getStatus()).thenReturn(CheckpointStatus.FAILED);
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        handler.updateStoreForTerminal(
                "s1",
                "coordinator",
                "u1",
                AutonomyLevel.FULL,
                AgentResult.finished("❌ Tool execution was denied and the task has been cancelled.", List.of()),
                "goal");

        verify(writer).saveAnswer("s1", "❌ Tool execution was denied and the task has been cancelled.");
        verify(agentStore)
                .saveAndFinalize(
                        "s1", "coordinator", "u1", AutonomyLevel.FULL, List.of(), CheckpointStatus.FAILED, "goal");
    }

    @Test
    void updateStoreForTerminalPersistsFailedReasonToChatMemory() {
        StatefulAgentStore agentStore = mock(StatefulAgentStore.class);
        AgentSseService sseService = mock(AgentSseService.class);
        ChannelPluginRegistry channelRegistry = mock(ChannelPluginRegistry.class);
        IntentClassificationService intentClassifier = mock(IntentClassificationService.class);
        ChatMemoryWriter writer = mock(ChatMemoryWriter.class);
        LoopResultHandler handler =
                new LoopResultHandler(agentStore, sseService, channelRegistry, intentClassifier, writer);

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getId()).thenReturn(123L);
        when(agentStore.save("s1", "coordinator", "u1", AutonomyLevel.FULL, List.of(), "goal"))
                .thenReturn(cp);

        handler.updateStoreForTerminal(
                "s1",
                "coordinator",
                "u1",
                AutonomyLevel.FULL,
                AgentResult.failed("Something failed", AgentResult.FailureCategory.SYSTEM_ERROR, List.of()),
                "goal");

        verify(writer).saveAnswer("s1", "Something failed");
        verify(agentStore).markFailed(123L, "Something failed");
    }
}
