package com.amsha.core.audit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amsha.core.domain.model.AuditEvent;
import com.amsha.core.domain.model.AuditEventRecord;
import com.amsha.persistence.api.AuditChainFinalizer;
import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.api.AuditEventStorePort;

import org.junit.jupiter.api.Test;

class AuditEventRecorderReceiptTest {
    @Test
    void recordWithReceiptReturnsPersistedIdAndClearsTemporaryKey() {
        AuditEventStorePort auditStore = mock(AuditEventStorePort.class);
        AuditHashUtil hashUtil = mock(AuditHashUtil.class);
        AuditEventRecorder recorder = new AuditEventRecorder();
        recorder.auditStore = auditStore;
        recorder.hashUtil = hashUtil;
        byte[] key = {1, 2, 3, 4};
        when(hashUtil.copyHmacKeyForLocalAppend()).thenReturn(key);
        doAnswer(invocation -> {
                    AuditEvent event = invocation.getArgument(0);
                    event.setPrevHash("previous");
                    event.setHmacHash("hmac");
                    return null;
                })
                .when(hashUtil)
                .calculateChainFields(any(AuditEvent.class), eq(null), same(key));
        when(auditStore.appendChained(any(AuditEventDto.class), any(AuditChainFinalizer.class)))
                .thenAnswer(invocation -> {
                    AuditEventDto seed = invocation.getArgument(0);
                    AuditChainFinalizer finalizer = invocation.getArgument(1);
                    return finalizer.finalizeChain(seed, 1, null);
                });

        AuditEvent event =
                AuditEventRecord.securityEvent("session-1", null, null, SecurityEventType.TOOL_ATTEMPT_ADMITTED, "{}");
        String persistedId = recorder.recordWithReceipt(event);

        assertEquals(event.getId().toString(), persistedId);
        assertEquals(1, event.getSequence());
        assertArrayEquals(new byte[key.length], key);
    }
}
