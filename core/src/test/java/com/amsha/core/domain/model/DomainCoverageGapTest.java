package com.amsha.core.domain.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.amsha.core.audit.AuditOutcome;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.SecretDto;

import org.junit.jupiter.api.Test;

class DomainCoverageGapTest {

    @Test
    void auditEventFluentMethodsDelegateToEverySetter() {
        AuditEvent event = mock(AuditEvent.class, CALLS_REAL_METHODS);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        BigDecimal cost = BigDecimal.ONE;

        assertSame(event, event.withId(id));
        assertSame(event, event.withSessionId("session"));
        assertSame(event, event.withUserId("user"));
        assertSame(event, event.withAgentRole("coder"));
        assertSame(event, event.withGoalHash("goal"));
        assertSame(event, event.withOutcome(AuditOutcome.FINISH));
        assertSame(event, event.withEventType(SecurityEventType.TOOL_EXECUTED));
        assertSame(event, event.withEventDetail("detail"));
        assertSame(event, event.withInputTokens(1));
        assertSame(event, event.withOutputTokens(2));
        assertSame(event, event.withUsdCost(cost));
        assertSame(event, event.withTimestamp(now));
        assertSame(event, event.withIterationsUsed(3));
        assertSame(event, event.withAppVersion("v1"));
        assertSame(event, event.withIdempotencyKey("key"));
        assertSame(event, event.withSequence(4));
        assertSame(event, event.withHmacHash("hmac"));
        assertSame(event, event.withPrevHash("prev"));

        verify(event).setId(id);
        verify(event).setSessionId("session");
        verify(event).setUserId("user");
        verify(event).setAgentRole("coder");
        verify(event).setGoalHash("goal");
        verify(event).setOutcome(AuditOutcome.FINISH);
        verify(event).setEventType(SecurityEventType.TOOL_EXECUTED);
        verify(event).setEventDetail("detail");
        verify(event).setInputTokens(1);
        verify(event).setOutputTokens(2);
        verify(event).setUsdCost(cost);
        verify(event).setTimestamp(now);
        verify(event).setIterationsUsed(3);
        verify(event).setAppVersion("v1");
        verify(event).setIdempotencyKey("key");
        verify(event).setSequence(4);
        verify(event).setHmacHash("hmac");
        verify(event).setPrevHash("prev");
    }

    @Test
    void encryptedSecretRoundTripsDtoAndMutableProperties() {
        byte[] ciphertext = {1, 2};
        byte[] nonce = {3, 4};
        EncryptedSecretRecord record =
                EncryptedSecretRecord.fromDto(new SecretDto("session", "name", 2, ciphertext, nonce));
        assertAll(
                () -> assertEquals("session", record.getSessionId()),
                () -> assertEquals("name", record.getKeyName()),
                () -> assertEquals(2, record.getEncryptionKeyVersion()),
                () -> assertArrayEquals(ciphertext, record.getCiphertext()),
                () -> assertArrayEquals(nonce, record.getNonce()));

        record.setSessionId("other");
        record.setKeyName("other-name");
        record.setEncryptionKeyVersion(3);
        record.setCiphertext(nonce);
        record.setNonce(ciphertext);
        assertEquals("other", record.getSessionId());
        assertEquals("other-name", record.getKeyName());
        assertEquals(3, record.getEncryptionKeyVersion());
    }
}
