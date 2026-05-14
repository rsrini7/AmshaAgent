package com.arcclaw.core.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.domain.model.AuditEventRecord;
import com.arcclaw.core.test.MockAuditEventStorePort;
import com.arcclaw.persistence.api.AuditEventDto;
import com.arcclaw.persistence.api.AuditEventStorePort;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class AuditVerifierTest {

    @Inject
    AuditVerifier verifier;

    @Inject
    AuditEventRecorder recorder;

    @Inject
    AuditEventStorePort storePort;

    @Inject
    Instance<MockAuditEventStorePort> mockStoreInstance;

    @Test
    @Transactional
    public void testValidChain() {
        String sessionId = "test-verify-valid-" + System.currentTimeMillis();
        recorder.record(AuditEventRecord.securityEvent(sessionId, "u", "r", SecurityEventType.LOOP_FINISH, null));
        recorder.record(AuditEventRecord.securityEvent(sessionId, "u", "r", SecurityEventType.LOOP_FINISH, null));
        recorder.record(AuditEventRecord.securityEvent(sessionId, "u", "r", SecurityEventType.LOOP_FINISH, null));

        AuditVerifier.VerificationResult result = verifier.verifyChain(sessionId);
        assertTrue(result.valid());
        assertEquals(3, result.totalRows());
    }

    @Test
    @Transactional
    public void testTamperedRowBreaksChain() throws Exception {
        String sessionId = "test-verify-tamper-" + System.currentTimeMillis();
        recorder.record(AuditEventRecord.securityEvent(sessionId, "u", "r", SecurityEventType.LOOP_FINISH, "A"));
        recorder.record(AuditEventRecord.securityEvent(sessionId, "u", "r", SecurityEventType.LOOP_FINISH, "B"));
        recorder.record(AuditEventRecord.securityEvent(sessionId, "u", "r", SecurityEventType.LOOP_FINISH, "C"));

        var events = storePort.findBySessionId(sessionId).stream()
                .map(AuditEventRecord::fromDto)
                .toList();
        assertEquals(3, events.size());
        UUID middleId = events.get(1).getId();

        // Get the real MockAuditEventStorePort instance (not the CDI proxy)
        MockAuditEventStorePort mockStore = mockStoreInstance.get();
        // Create a tampered copy of the middle DTO with altered eventDetail
        List<AuditEventDto> dtos = mockStore.findBySessionId(sessionId);
        AuditEventDto middle = dtos.get(1);
        AuditEventDto tampered = new AuditEventDto(
                middle.id(), middle.sessionId(), middle.userId(), middle.agentRole(),
                middle.goalHash(), middle.outcome(), middle.eventType(),
                "TAMPERED",
                middle.inputTokens(), middle.outputTokens(), middle.usdCost(),
                middle.timestamp(), middle.iterationsUsed(),
                middle.appVersion(), middle.idempotencyKey(),
                middle.sequence(), middle.hmacHash(), middle.prevHash());
        mockStore.replaceDto(sessionId, 1, tampered);

        AuditVerifier.VerificationResult result = verifier.verifyChain(sessionId);
        assertFalse(result.valid());
        assertEquals(middleId.toString(), result.brokenAtId());
        assertEquals(3, result.totalRows());
    }
}
