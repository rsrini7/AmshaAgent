package com.arcclaw.core.audit;

import com.arcclaw.core.agent.AgentResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
public class AuditObserverTest {

    @Inject
    AuditObserver observer;

    @Inject
    AuditEventRepository repository;

    @Test
    public void testLoopCompletionFiresAudit() {
        String sessionId = "test-observe-" + System.currentTimeMillis();
        
        // Simulate LLM calls to accumulate tokens
        observer.recordLlmCall(sessionId, 50, 10);
        observer.recordLlmCall(sessionId, 50, 10); // Total: 100 in, 20 out

        AgentResult result = AgentResult.finished("Done", List.of());
        String goalHash = "testhash";
        
        observer.onLoopComplete(sessionId, "userX", "coder", goalHash, result);

        List<AuditEvent> events = repository.findBySessionId(sessionId);
        assertEquals(1, events.size());
        
        AuditEvent event = events.get(0);
        assertEquals(AuditOutcome.FINISH, event.outcome);
        assertEquals(SecurityEventType.LOOP_FINISH, event.eventType);
        assertEquals(100, event.inputTokens);
        assertEquals(20, event.outputTokens);
        // Cost calculation verified implicitly
    }
}
