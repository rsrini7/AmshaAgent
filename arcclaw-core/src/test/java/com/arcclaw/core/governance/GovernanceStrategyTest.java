package com.arcclaw.core.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.arcclaw.core.audit.AuditHashUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GovernanceStrategyTest {

    @Inject
    GovernanceStrategy governanceLayer; // Must inject via interface

    @InjectMock
    AuditHashUtil auditHashUtil;

    @BeforeEach
    void setupHmacMock() {
        lenient().when(auditHashUtil.sha256hex(anyString())).thenCallRealMethod();
        lenient().when(auditHashUtil.hmacHex(anyString())).thenAnswer(inv -> {
            String data = inv.getArgument(0);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        });
        lenient().doCallRealMethod().when(auditHashUtil).calculateChainFields(any(), any());
    }

    @Test
    void interfaceIsInjectableInsteadOfConcreteClass() {
        assertNotNull(governanceLayer);
        assertTrue(governanceLayer instanceof GovernanceLayer);
    }

    @Test
    void recordLlmCallThrowsBudgetExceededWhenLimitHit() {
        // Force exceed by recording massive token counts
        assertThrows(
                BudgetExceededException.class,
                () -> governanceLayer.recordLlmCall("test-session-999", 10_000_000, 10_000_000));
        governanceLayer.clearSession("test-session-999");
    }

    @Test
    void clearSessionResetsState() {
        governanceLayer.recordLlmCall("session-clear-test", 10, 10);
        governanceLayer.clearSession("session-clear-test");
        assertEquals(0.0, governanceLayer.getSessionCost("session-clear-test"));
    }
}
