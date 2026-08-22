package com.amsha.core.agent.loop;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.state.StatefulAgentStore;
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
}
