package com.amsha.core.governance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.audit.AuditHashUtil;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GovernanceLayerAssertBudgetTest {

    @Inject
    GovernanceLayer governanceLayer;

    @InjectMock
    AuditHashUtil auditHashUtil;

    private String sessionId;

    @BeforeEach
    void setup() {
        sessionId = UUID.randomUUID().toString();
        lenient().when(auditHashUtil.sha256hex(anyString())).thenCallRealMethod();
        lenient().when(auditHashUtil.hmacHex(anyString())).thenAnswer(inv -> {
            String data = inv.getArgument(0);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        });
        lenient().doCallRealMethod().when(auditHashUtil).calculateChainFields(any(), any());
    }

    @AfterEach
    void tearDown() {
        governanceLayer.clearSession(sessionId);
    }

    @Test
    void testAssertWithinBudgetUnderLimit() {
        WorkspaceContext.run(sessionId, "test-user", "test-chat", AutonomyLevel.FULL, "coordinator", () -> {
            governanceLayer.recordLlmCall(sessionId, 100, 100);
            assertDoesNotThrow(() -> governanceLayer.assertWithinBudget(sessionId));
        });
    }

    @Test
    void testAssertWithinBudgetAtLimitThrows() {
        WorkspaceContext.run(sessionId, "test-user", "test-chat", AutonomyLevel.FULL, "coordinator", () -> {
            // Budget is $10.00.
            // Record massive tokens to exceed it
            try {
                governanceLayer.recordLlmCall(sessionId, 2000000, 1000000); // (~ $6 + $15 = $21)
            } catch (BudgetWarningException | BudgetExceededException _) {
                /* expected — exceeding budget */
            }

            assertThrows(BudgetExceededException.class, () -> governanceLayer.assertWithinBudget(sessionId));
        });
    }

    @Test
    void testAtomicWarningSentConcurrency() throws InterruptedException {
        // High-concurrency test for AtomicBoolean warningSent
        ExecutorService executor = Executors.newFixedThreadPool(10);
        AtomicInteger warningCount = new AtomicInteger(0);
        int totalCalls = 100;
        CountDownLatch latch = new CountDownLatch(totalCalls);

        WorkspaceContext.run(sessionId, "test-user", "test-chat", AutonomyLevel.FULL, "coordinator", () -> {
            for (int i = 0; i < totalCalls; i++) {
                executor.submit(() -> {
                    try {
                        // All threads hitting the warning threshold simultaneously
                        governanceLayer.recordLlmCall(sessionId, 500000, 500000); // hits 1.5 + 7.5 = 9.0 (90%)
                    } catch (BudgetWarningException _) {
                        warningCount.incrementAndGet();
                    } catch (Exception _) {
                    } finally {
                        latch.countDown();
                    }
                });
            }
        });

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // ONLY ONE thread should have successfully compareAndSet from false -> true
        assertEquals(
                1, warningCount.get(), "BudgetWarningException should only be thrown once due to AtomicBoolean CAS");
    }

    @Test
    void testThresholdCheckBeforeCAS() {
        WorkspaceContext.run(sessionId, "test-user", "test-chat", AutonomyLevel.FULL, "coordinator", () -> {
            // Call 1: Small cost, no warning
            governanceLayer.recordLlmCall(sessionId, 100, 100);
            assertEquals(0, governanceLayer.getSessionCost(sessionId), 0.02); // cost is ~0.0018

            // Call 2: Hit warning threshold
            assertThrows(BudgetWarningException.class, () -> governanceLayer.recordLlmCall(sessionId, 500000, 500000));
        });
    }
}
