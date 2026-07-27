package com.amsha.core.observability;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AgentObserverTest {

    @Test
    void defaultOnStepCompleteDoesNotThrow() {
        AgentObserver observer = new AgentObserver() {
            @Override
            public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {}
        };
        observer.onStepComplete("sess", "role", null);
    }

    @Test
    void defaultOnLoopCompleteDoesNotThrow() {
        AgentObserver observer = new AgentObserver() {
            @Override
            public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {}
        };
        observer.onLoopComplete("sess", "user", "role", "hash", null);
    }

    @Test
    void recordLlmCallIsCalled() {
        var called = new boolean[] {false};
        AgentObserver observer = new AgentObserver() {
            @Override
            public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
                called[0] = true;
                assertEquals("sess", sessionId);
                assertEquals(100, inputTokens);
                assertEquals(50, outputTokens);
            }
        };
        observer.recordLlmCall("sess", 100, 50);
        assertTrue(called[0]);
    }
}
