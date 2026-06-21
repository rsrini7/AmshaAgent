package com.amsha.core.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.amsha.core.domain.model.AuditEventRecord;
import com.amsha.core.test.MockAuditEventStorePort;
import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.api.AuditEventStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

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

    @InjectMock
    AuditHashUtil auditHashUtil;

    @BeforeEach
    void setupHmacMock() {
        when(auditHashUtil.sha256hex(anyString())).thenCallRealMethod();
        when(auditHashUtil.hmacHex(anyString())).thenAnswer(inv -> {
            String data = inv.getArgument(0);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        });
        doCallRealMethod().when(auditHashUtil).calculateChainFields(any(), any());
    }

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
                middle.id(),
                middle.sessionId(),
                middle.userId(),
                middle.agentRole(),
                middle.goalHash(),
                middle.outcome(),
                middle.eventType(),
                "TAMPERED",
                middle.inputTokens(),
                middle.outputTokens(),
                middle.usdCost(),
                middle.timestamp(),
                middle.iterationsUsed(),
                middle.appVersion(),
                middle.idempotencyKey(),
                middle.sequence(),
                middle.hmacHash(),
                middle.prevHash());
        mockStore.replaceDto(sessionId, 1, tampered);

        AuditVerifier.VerificationResult result = verifier.verifyChain(sessionId);
        assertFalse(result.valid());
        assertEquals(middleId.toString(), result.brokenAtId());
        assertEquals(3, result.totalRows());
    }
}
