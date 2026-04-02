package com.arcclaw.core.agent.loop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ParallelToolStepExecutorPreExecTest {

    @Mock ToolInvocationGuard guard;
    @Mock ToolInvoker toolInvoker;
    @Mock ApprovalCheckpointManager checkpointManager;
    @Mock GovernanceStrategy governanceLayer;
    @Mock RateLimitTracker rateLimitTracker;
    @Mock Instance<AgentObserver> observers;
    
    ObjectMapper objectMapper = new ObjectMapper();

    ParallelToolStepExecutor executor;

    @BeforeEach
    void setup() {
        executor = new ParallelToolStepExecutor();
        executor.objectMapper = objectMapper;
        executor.guard = guard;
        executor.toolInvoker = toolInvoker;
        executor.checkpointManager = checkpointManager;
        executor.governanceLayer = governanceLayer;
        executor.rateLimitTracker = rateLimitTracker;
        executor.observers = observers;
        executor.preExecutableTools = Optional.of(List.of("safe_read"));
    }

    @Test
    void executeWithMixedTools_PreExecutesSafeTool() {
        String sessionId = "123-456";
        String agentRole = "COORDINATOR";
        
        AgentExecutionContext context = new AgentExecutionContext(
            sessionId, "user1", "chat1", agentRole, "goal", AutonomyLevel.SUPERVISED, 5, null, null);
            
        var calls = List.of(
            new ParallelToolCall("c1", "safe_read", Map.of(), "{}", null, true),
            new ParallelToolCall("c2", "unsafe_write", Map.of(), "{}", null, true)
        );
        
        ReasoningStep batchStep = ReasoningStep.parallelBatch(
            "thought",
            Map.of("batch", calls),
            0, 0
        ).withObservation("");

        // First tool triggers HITL
        when(guard.evaluate(any(), any(), eq("safe_read"), any(), any(), any(), any()))
            .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());

        when(guard.evaluate(any(), any(), eq("unsafe_write"), any(), any(), any(), any()))
            .thenReturn(new ToolInvocationGuard.GuardDecision.PauseForApproval("reason", "unsafe_write"));

        // Mock safe tool invocation
        when(toolInvoker.invoke(eq("safe_read"), any(), eq(sessionId)))
            .thenReturn(ToolResponse.success("pre-exec-data"));

        executor.execute(context, Collections.emptyList(), batchStep, 0);

        // Verify pre-execution happened
        verify(toolInvoker, times(1)).invoke(eq("safe_read"), any(), any());
        
        // Verify persistBatchWithPreExecuted was called with BOTH tools in originalBatchJson
        // BUT only 'unsafe_write' in hitlCalls.
        verify(checkpointManager).persistBatchWithPreExecuted(
            eq(context),
            contains("unsafe_write"),
            contains("safe_read"), // original contains both
            argThat(list -> list.size() == 1 && list.get(0).toolName().equals("unsafe_write")),
            contains("pre-exec-data"),
            anyList(),
            any(),
            any()
        );
    }
}
