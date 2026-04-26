package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@QuarkusTest
class AgentLoopContextTest {

    @Inject
    AgentLoop agentLoop;

    @InjectMock
    AgentStateMachineRunner stateMachineRunner;

    @BeforeEach
    void setUp() {
        // No-op. @InjectMock handles initialization.
    }

    @Test
    void testShortRunOverloadActivatesRequestContext() {
        ReasoningEngine engine = mock(ReasoningEngine.class);
        
        // We mock WorkspaceScopeHolder to simulate the failure described in the prompt.
        try (MockedStatic<WorkspaceScopeHolder> mockedHolder = Mockito.mockStatic(WorkspaceScopeHolder.class)) {
            mockedHolder.when(WorkspaceScopeHolder::getIfActive).thenAnswer(inv -> {
                // Manually check if RequestScope context is active
                try {
                    if (!CDI.current().getBeanManager().getContext(RequestScoped.class).isActive()) {
                        throw new ContextNotActiveException("Simulated failure - RequestContext is NOT active");
                    }
                } catch (Exception e) {
                    throw new ContextNotActiveException("Simulated failure - RequestContext is NOT active");
                }
                return "test-session";
            });

            // Mock the runner completion
            when(stateMachineRunner.run(any(), eq(engine), any())).thenReturn(
                    AgentResult.finished("Success", Collections.emptyList()));

            // This call should not throw because @ActivateRequestContext is now on BOTH overloads
            assertDoesNotThrow(() -> agentLoop.run("test goal", engine));
            assertDoesNotThrow(() -> agentLoop.run("test goal", engine, 5));
        }
    }
}
