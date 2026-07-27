package com.amsha.core.memory.tiered;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.config.TieredMemoryConfig;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.CoreMemoryStorePort;
import com.amsha.persistence.api.VersionConflictException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for CoreMemoryService, focusing on CAS atomic append behavior.
 */
@SuppressWarnings("java:S1192")
class CoreMemoryServiceTest {

    @Mock
    CoreMemoryStorePort coreStore;

    @Mock
    TieredMemoryConfig tieredConfig;

    @Mock
    TieredMemoryConfig.CoreMemoryConfig coreMemoryConfig;

    CoreMemoryService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        service = new CoreMemoryService();
        service.coreStore = coreStore;
        service.config = tieredConfig;
        when(tieredConfig.coreMemory()).thenReturn(coreMemoryConfig);
        when(coreMemoryConfig.maxBlocksPerUser()).thenReturn(20);
        when(coreMemoryConfig.maxCharsPerBlock()).thenReturn(2000);
    }

    @Test
    void testReadAllAndGetAndDelete() {
        when(coreStore.read("user1")).thenReturn(List.of());
        assertTrue(service.readAll("user1").isEmpty());

        when(coreStore.get("user1", "user_profile")).thenReturn(Optional.empty());
        assertTrue(service.get("user1", "user_profile").isEmpty());

        service.delete("user1", "user_profile");
        verify(coreStore).delete("user1", "user_profile");
    }

    @Test
    void testUpsertSuccess() {
        when(coreStore.get("user1", "user_profile")).thenReturn(Optional.empty());
        when(coreStore.read("user1")).thenReturn(List.of());

        assertDoesNotThrow(() -> service.upsert("user1", "user_profile", "content"));
        verify(coreStore).upsert(eq("user1"), eq("user_profile"), eq("content"), eq(0));
    }

    @Test
    void testUpsertInvalidKey() {
        assertThrows(IllegalArgumentException.class, () -> service.upsert("user1", "invalid_key", "content"));
    }

    @Test
    void testAppend_casRetriesOnConflict_preservesBothWrites() {
        // Simulate CAS conflict on first attempt, success on second
        AtomicInteger version = new AtomicInteger(0);
        when(coreStore.get("user1", "active_task")).thenAnswer(invocation -> {
            int v = version.get();
            if (v == 0) return Optional.empty(); // first read: no block
            return Optional.of(new CoreMemoryBlockDto("id1", "user1", "active_task", "Alice", v, null, null));
        });

        // First upsert: conflict (someone else wrote "Alice")
        Mockito.doAnswer(invocation -> {
                    int expected = invocation.getArgument(3, Integer.class);
                    if (expected == 0 && version.get() == 0) {
                        version.set(1); // simulate another writer created the block
                        throw new VersionConflictException("active_task", 0, 1);
                    }
                    version.set(expected + 1);
                    return null;
                })
                .doNothing() // second upsert succeeds
                .when(coreStore)
                .upsert(eq("user1"), eq("active_task"), anyString(), anyInt());

        when(coreStore.read("user1")).thenReturn(List.of());

        String result = service.append("user1", "active_task", "Bob");

        // On conflict retry: reads "Alice", appends "Bob" → "Alice\nBob"
        assertTrue(result.contains("Alice"), "Should preserve content from first writer");
        assertTrue(result.contains("Bob"), "Should include new content");
    }

    @Test
    void testAppend_createsNewBlockWhenEmpty() {
        when(coreStore.get("user1", "user_profile")).thenReturn(Optional.empty());
        when(coreStore.read("user1")).thenReturn(List.of());

        String result = service.append("user1", "user_profile", "New user data");

        assertEquals("New user data", result);
    }

    @Test
    void testAppend_invalidBlockKey_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.append("user1", "bad_key", "content"));
    }

    @Test
    void testAppend_oversizeRejection_throws() {
        // With maxCharsPerBlock=2000, appending content that would exceed should reject
        String existing = "x".repeat(1900);
        when(coreStore.get("user1", "user_profile"))
                .thenReturn(
                        Optional.of(new CoreMemoryBlockDto("id1", "user1", "user_profile", existing, 1, null, null)));

        // Append 200 chars → combined 2100 > 2000 → should reject, not truncate
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> service.append("user1", "user_profile", "y".repeat(200)));
        assertTrue(
                ex.getMessage().contains("exceed max block size"),
                "Should reject oversize append, not silently truncate");
        assertTrue(ex.getMessage().contains("core_memory_replace"), "Should guide agent to use core_memory_replace");
    }

    @Test
    void testAppend_blockLimitReached_throws() {
        when(coreStore.get("user1", "user_profile")).thenReturn(Optional.empty());
        // 20 blocks already exist (max)
        List<CoreMemoryBlockDto> fullBlocks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fullBlocks.add(new CoreMemoryBlockDto("id" + i, "user1", "block_" + i, "data", 1, null, null));
        }
        when(coreStore.read("user1")).thenReturn(fullBlocks);

        assertThrows(IllegalStateException.class, () -> service.append("user1", "user_profile", "content"));
    }

    @Test
    void testAppend_concurrentAppends_noLostUpdates() throws Exception {
        // Single immutable snapshot prevents torn reads between version and content
        record Snapshot(int version, String content) {}
        AtomicReference<Snapshot> state = new AtomicReference<>(new Snapshot(0, ""));
        AtomicInteger successCount = new AtomicInteger(0);

        when(coreStore.get("user1", "active_task")).thenAnswer(invocation -> {
            Snapshot s = state.get();
            if (s.version() == 0) return Optional.empty();
            return Optional.of(
                    new CoreMemoryBlockDto("id1", "user1", "active_task", s.content(), s.version(), null, null));
        });

        Mockito.doAnswer(invocation -> {
                    String content = invocation.getArgument(2, String.class);
                    int expected = invocation.getArgument(3, Integer.class);

                    // Atomic CAS: compareAndSwap on single reference
                    Snapshot current = state.get();
                    if (expected == current.version()) {
                        boolean swapped = state.compareAndSet(current, new Snapshot(current.version() + 1, content));
                        if (swapped) {
                            successCount.incrementAndGet();
                            return null;
                        }
                    }
                    // Conflict: someone else changed the state
                    throw new VersionConflictException(
                            "active_task", expected, state.get().version());
                })
                .when(coreStore)
                .upsert(eq("user1"), eq("active_task"), anyString(), anyInt());

        when(coreStore.read("user1")).thenReturn(List.of());

        // Run two appends concurrently
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<String>> futures = new CopyOnWriteArrayList<>();

        futures.add(executor.submit(() -> {
            startLatch.await();
            return service.append("user1", "active_task", "fact-A");
        }));
        futures.add(executor.submit(() -> {
            startLatch.await();
            return service.append("user1", "active_task", "fact-B");
        }));

        startLatch.countDown(); // release both simultaneously
        futures.get(0).get();
        futures.get(1).get();

        // Both facts must be present in the final accumulated content
        String finalContent = state.get().content();
        assertTrue(finalContent.contains("fact-A"), "First append must be preserved");
        assertTrue(finalContent.contains("fact-B"), "Second append must be preserved");

        executor.shutdown();
    }
}
