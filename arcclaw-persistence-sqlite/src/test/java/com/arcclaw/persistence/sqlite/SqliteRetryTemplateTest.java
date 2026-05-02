package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.inject.Instance;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqliteRetryTemplateTest {

    private SqliteRetryTemplate template;
    private MeterRegistry meterRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        template = new SqliteRetryTemplate();
        meterRegistry = new SimpleMeterRegistry();
        
        Instance<MeterRegistry> instance = mock(Instance.class);
        when(instance.get()).thenReturn(meterRegistry);
        when(instance.isUnsatisfied()).thenReturn(false);

        Field field = SqliteRetryTemplate.class.getDeclaredField("meterRegistry");
        field.setAccessible(true);
        field.set(template, instance);
    }

    @Test
    void testSuccessfulExecution() throws Exception {
        String result = template.execute("test", 3, 10, () -> "success");
        assertEquals("success", result);
        assertEquals(0, meterRegistry.counter("arcclaw.persistence.sqlite.retry.attempt", "operation", "test").count());
    }

    @Test
    void testRetryOnBusy() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = template.execute("test-retry", 3, 10, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("database is locked"); // simulates BUSY
            }
            return "recovered";
        });
        assertEquals(3, attempts.get());
        assertEquals("recovered", result);
        
        // 2 retries = 2 attempt increments
        assertEquals(2.0, meterRegistry.counter("arcclaw.persistence.sqlite.retry.attempt", "operation", "test-retry").count());
        assertEquals(0.0, meterRegistry.counter("arcclaw.persistence.sqlite.retry.exhausted", "operation", "test-retry").count());
    }

    @Test
    void testExhaustion() {
        AtomicInteger attempts = new AtomicInteger(0);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            template.execute("test-exhaust", 3, 10, () -> {
                attempts.incrementAndGet();
                throw new RuntimeException("database is locked");
            });
        });
        assertTrue(ex.getMessage().contains("after 3 attempts"));
        assertEquals(3, attempts.get());
        
        // 3 failures = 3 attempt increments in the current BackoffRetryExecutor implementation
        assertEquals(3.0, meterRegistry.counter("arcclaw.persistence.sqlite.retry.attempt", "operation", "test-exhaust").count());
        // 1 exhaustion event
        assertEquals(1.0, meterRegistry.counter("arcclaw.persistence.sqlite.retry.exhausted", "operation", "test-exhaust").count());
    }

    @Test
    void testNoRetryOnOtherException() {
        AtomicInteger attempts = new AtomicInteger(0);
        assertThrows(RuntimeException.class, () -> {
            template.execute("test-other", 3, 10, () -> {
                attempts.incrementAndGet();
                throw new RuntimeException("other error");
            });
        });
        assertEquals(1, attempts.get(), "Should not retry on non-busy error");
        assertEquals(0.0, meterRegistry.counter("arcclaw.persistence.sqlite.retry.attempt", "operation", "test-other").count());
        assertEquals(0.0, meterRegistry.counter("arcclaw.persistence.sqlite.retry.exhausted", "operation", "test-other").count());
    }
}
