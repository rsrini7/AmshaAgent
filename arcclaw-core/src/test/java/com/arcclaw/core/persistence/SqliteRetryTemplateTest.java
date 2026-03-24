package com.arcclaw.core.persistence;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;

class SqliteRetryTemplateTest {

    private final SqliteRetryTemplate template = new SqliteRetryTemplate();

    @Test
    void testSuccessfulExecution() throws Exception {
        String result = template.execute("test", 3, 10, () -> "success");
        assertEquals("success", result);
    }

    @Test
    void testRetryOnBusy() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = template.execute("test", 3, 10, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("database is locked"); // simulates BUSY
            }
            return "recovered";
        });
        assertEquals(3, attempts.get());
        assertEquals("recovered", result);
    }

    @Test
    void testNoRetryOnNonBusy() {
        AtomicInteger attempts = new AtomicInteger(0);
        assertThrows(RuntimeException.class, () -> {
            template.execute("test", 3, 10, () -> {
                attempts.incrementAndGet();
                throw new RuntimeException("other error");
            });
        });
        assertEquals(1, attempts.get(), "Should not retry on non-busy error");
    }

    @Test
    void testExhaustion() {
        AtomicInteger attempts = new AtomicInteger(0);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            template.execute("test", 3, 10, () -> {
                attempts.incrementAndGet();
                throw new RuntimeException("database is locked");
            });
        });
        assertTrue(ex.getMessage().contains("after 3 attempts"));
        assertEquals(3, attempts.get());
    }
}
