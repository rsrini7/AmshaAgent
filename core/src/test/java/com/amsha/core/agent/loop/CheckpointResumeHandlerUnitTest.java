package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.persistence.api.CronStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckpointResumeHandlerUnitTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    ToolRegistry toolRegistry;

    @Mock
    ObjectMapper objectMapper;

    @Mock
    AgentLoop agentLoop;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    ChannelPluginRegistry channelPluginRegistry;

    @Mock
    CronStorePort cronStore;

    @Mock
    CliResultBridge cliResultBridge;

    @Mock
    ExecutionFailureRecoveryService recoveryService;

    @Mock
    PersonaEscalationService escalationService;

    private CheckpointResumeHandler resumeHandler;

    @BeforeEach
    void setUp() {
        resumeHandler = new CheckpointResumeHandler(
                agentStore,
                toolRegistry,
                objectMapper,
                agentLoop,
                reasoningEngine,
                channelPluginRegistry,
                cronStore,
                cliResultBridge,
                recoveryService,
                escalationService);
    }

    @Test
    void testResumeAfterBatchDecisionClaimFails() {
        when(agentStore.claimForResume("s1", "coder")).thenReturn(false);

        assertDoesNotThrow(() -> resumeHandler.resumeAfterBatchDecision("s1", "coder", "u1", "c1", "telegram"));
        verify(agentStore, never()).load(anyString(), anyString());
    }

    @Test
    void testResumeAfterBatchDecisionCheckpointNull() {
        when(agentStore.claimForResume("s1", "coder")).thenReturn(true);
        when(agentStore.load("s1", "coder")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> resumeHandler.resumeAfterBatchDecision("s1", "coder", "u1", "c1", "telegram"));
        verify(agentStore).releaseResumeClaim("s1", "coder");
        verify(agentStore).clearBatchResuming("s1");
    }
}
