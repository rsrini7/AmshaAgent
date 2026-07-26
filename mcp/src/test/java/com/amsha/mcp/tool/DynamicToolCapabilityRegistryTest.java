package com.amsha.mcp.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DynamicToolCapabilityRegistryTest {

    private DynamicToolCapabilityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DynamicToolCapabilityRegistry();
    }

    private DynamicToolCapability createTool(String name) {
        return new DynamicToolCapability(name, "desc", "server", McpTransportType.STDIO, false, "{}");
    }

    @Test
    void testRegisterAndFind() {
        DynamicToolCapability tool = createTool("test_tool");
        registry.register(tool);

        Optional<DynamicToolCapability> found = registry.find("test_tool");
        assertTrue(found.isPresent());
        assertEquals(tool, found.get());
        assertTrue(registry.isRegistered("test_tool"));
    }

    @Test
    void testFindUnknown() {
        Optional<DynamicToolCapability> found = registry.find("unknown_tool");
        assertTrue(found.isEmpty());
        assertFalse(registry.isRegistered("unknown_tool"));
    }

    @Test
    void testDeregister() {
        DynamicToolCapability tool = createTool("temp_tool");
        registry.register(tool);
        assertTrue(registry.isRegistered("temp_tool"));

        registry.deregister("temp_tool");
        assertFalse(registry.isRegistered("temp_tool"));
        assertTrue(registry.find("temp_tool").isEmpty());
    }

    @Test
    void testRegisterNull() {
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    void testGetAllReflectsCurrentState() {
        assertTrue(registry.getAll().isEmpty());

        registry.register(createTool("tool1"));
        registry.register(createTool("tool2"));

        Collection<DynamicToolCapability> all = registry.getAll();
        assertEquals(2, all.size());

        registry.deregister("tool1");
        Collection<DynamicToolCapability> allAfterDeregister = registry.getAll();
        assertEquals(1, allAfterDeregister.size());
    }

    @Test
    @DisplayName("Verify no CME occurs when registering and iterating concurrently (B2 Hardening)")
    void testConcurrentRegisterAndDeregisterNoCme() throws InterruptedException {
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.execute(() -> {
                try {
                    if (idx % 2 == 0) {
                        for (int j = 0; j < 100; j++) {
                            registry.register(createTool("tool-" + idx + "-" + j));
                        }
                    } else {
                        for (int j = 0; j < 100; j++) {
                            var _ = registry.getAll().stream().count();
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "Test timed out");
        assertEquals(
                0,
                failures.get(),
                "ConcurrentModificationException or other error occurred during concurrent operation");
    }
}
