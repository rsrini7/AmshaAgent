package com.arcclaw.core.tool;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.arcclaw.core.agent.loop.ToolInvocationGuard;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tools.GithubTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GithubToolGuardInterceptorTest {

    private GithubToolGuardInterceptor interceptor;
    private StatefulAgentStore agentStore;
    private ChatMemoryStore chatMemoryStore;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        interceptor = new GithubToolGuardInterceptor();
        agentStore = mock(StatefulAgentStore.class);
        chatMemoryStore = mock(ChatMemoryStore.class);
        objectMapper = new ObjectMapper();

        interceptor.agentStore = agentStore;
        interceptor.chatMemoryStore = chatMemoryStore;
        interceptor.objectMapper = objectMapper;
    }

    @Test
    void testGithubBlockedWhenGoalHasNoKeywords() {
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.empty());
        when(chatMemoryStore.getMessages(anyString())).thenReturn(List.of());

        Optional<ToolInvocationGuard.GuardDecision> result = interceptor.evaluate(
                "s1", "coordinator", AutonomyLevel.SUPERVISED, GithubTool.NAME, Map.of(), List.of(), "abc def", List.of());

        assertTrue(result.isPresent());
        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, result.get());
        assertTrue(((ToolInvocationGuard.GuardDecision.Blocked) result.get()).observation().contains("GitHub tool blocked"));
    }

    @Test
    void testGithubPreApprovedReturnsEmpty() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(GithubTool.NAME);
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.of(cp));

        Optional<ToolInvocationGuard.GuardDecision> result = interceptor.evaluate(
                "s1", "coordinator", AutonomyLevel.SUPERVISED, GithubTool.NAME, Map.of(), List.of(), "how are you?", List.of());

        assertTrue(result.isEmpty(), "Pre-approved GitHub should pass through to HITL layer");
    }
}
