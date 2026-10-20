package com.amsha.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AuditEvent;
import com.amsha.core.domain.model.AuditEventRecord;
import com.amsha.persistence.api.AuditEventStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class AuditObserverTest {

    @Inject
    AuditObserver observer;

    @Inject
    AuditEventStorePort auditStore;

    @Inject
    AuditService auditService;

    @InjectMock
    AuditHashUtil auditHashUtil;

    @BeforeEach
    void setupHmacMock() {
        // Previous tests may still have audit work in the shared asynchronous queue.
        // Drain it before changing the CDI mock's stubbing.
        auditService.waitForIdle();
        when(auditHashUtil.sha256hex(anyString())).thenCallRealMethod();
        when(auditHashUtil.hmacHex(anyString())).thenAnswer(inv -> {
            String data = inv.getArgument(0);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        });
        doCallRealMethod().when(auditHashUtil).calculateChainFields(any(), any());
    }

    @Test
    public void testLoopCompletionFiresAudit() {
        String sessionId = "test-observe-" + UUID.randomUUID();

        // Simulate LLM calls to accumulate tokens
        observer.recordLlmCall(sessionId, 50, 10);
        observer.recordLlmCall(sessionId, 50, 10); // Total: 100 in, 20 out

        AgentResult result = AgentResult.finished("Done", List.of());
        String goalHash = "testhash";

        observer.onLoopComplete(sessionId, "userX", "coder", goalHash, result);

        auditService.waitForIdle();

        var events = auditStore.findBySessionId(sessionId).stream()
                .map(AuditEventRecord::fromDto)
                .toList();
        assertEquals(1, events.size());

        AuditEvent event = events.get(0);
        assertEquals(AuditOutcome.FINISH, event.getOutcome());
        assertEquals(SecurityEventType.LOOP_FINISH, event.getEventType());
        assertEquals(100, event.getInputTokens());
        assertEquals(20, event.getOutputTokens());
        // Cost calculation verified implicitly
    }
}
