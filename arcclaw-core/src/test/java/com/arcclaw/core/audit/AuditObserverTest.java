package com.arcclaw.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import jakarta.inject.Inject;

import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.domain.model.AuditEvent;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class AuditObserverTest {

    @Inject
    AuditObserver observer;

    @Inject
    AuditEventRepository repository;

    @Inject
    AuditService auditService;

    @Test
    public void testLoopCompletionFiresAudit() {
        String sessionId = "test-observe-" + System.currentTimeMillis();
        
        // Simulate LLM calls to accumulate tokens
        observer.recordLlmCall(sessionId, 50, 10);
        observer.recordLlmCall(sessionId, 50, 10); // Total: 100 in, 20 out

        AgentResult result = AgentResult.finished("Done", List.of());
        String goalHash = "testhash";
        
        observer.onLoopComplete(sessionId, "userX", "coder", goalHash, result);
        
        auditService.waitForIdle();

        List<AuditEvent> events = repository.findBySessionId(sessionId);
        assertEquals(1, events.size());

        AuditEvent event = events.get(0);
        assertEquals(AuditOutcome.FINISH, event.getOutcome());
        assertEquals(SecurityEventType.LOOP_FINISH, event.getEventType());
        assertEquals(100, event.getInputTokens());
        assertEquals(20, event.getOutputTokens());
        // Cost calculation verified implicitly
    }
}
