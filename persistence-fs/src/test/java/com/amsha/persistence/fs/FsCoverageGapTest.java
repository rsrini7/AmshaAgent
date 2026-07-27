package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.PromptDto;
import com.amsha.persistence.api.VersionConflictException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FsCoverageGapTest {

    private FilesystemFileStore store;

    @BeforeEach
    void setUp() {
        store = mock(FilesystemFileStore.class);
    }

    @Test
    void cronRunsSupportLookupFilteringSortingAndPagination() {
        FsCronRunStoreAdapter adapter = new FsCronRunStoreAdapter();
        adapter.store = store;
        Instant now = Instant.now();
        CronRunDto newer = run("new", "cron-a", "SUCCESS", now);
        CronRunDto older = run("old", "cron-a", "FAILED", now.minusSeconds(10));
        CronRunDto undated = run("none", "cron-b", "SUCCESS", null);

        when(store.listDirs(List.of("cron-runs"))).thenReturn(List.of("cron-a", "cron-b"));
        when(store.list(List.of("cron-runs", "cron-a"), ".json")).thenReturn(List.of("old.json", "new.json"));
        when(store.list(List.of("cron-runs", "cron-b"), ".json")).thenReturn(List.of("none.json"));
        when(store.readOptional(anyList(), eq(CronRunDto.class))).thenAnswer(invocation -> {
            String file = ((List<String>) invocation.getArgument(0)).getLast();
            return Optional.of(
                    switch (file) {
                        case "new.json" -> newer;
                        case "old.json" -> older;
                        default -> undated;
                    });
        });

        adapter.save(newer);
        assertEquals("new", adapter.findById("new").orElseThrow().id());
        assertTrue(adapter.findById(" ").isEmpty());
        assertEquals(
                List.of("new", "old"),
                adapter.findByCronId("cron-a", 0, 10).stream()
                        .map(CronRunDto::id)
                        .toList());
        assertTrue(adapter.findByCronId("cron-a", 9, 10).isEmpty());
        assertEquals(2, adapter.countByCronId("cron-a"));
        assertEquals(2, adapter.findAll("SUCCESS", 0, 10).size());
        assertEquals(1, adapter.findAll(null, 1, 1).size());
        assertTrue(adapter.findAll(null, 9, 1).isEmpty());
        assertEquals(1, adapter.countAll("FAILED"));
    }

    @Test
    void promptsSupportAllIndicesAndDeletionPaths() {
        FsPromptStoreAdapter adapter = new FsPromptStoreAdapter();
        adapter.store = store;
        PromptDto active = prompt("p1", "ACTIVE");
        PromptDto draft = prompt("p2", "DRAFT");
        when(store.list(List.of("prompts", "by-id"), ".json")).thenReturn(List.of("p1.json", "p2.json"));
        when(store.list(List.of("prompts", "by-role", "coder"), ".json")).thenReturn(List.of("system.json"));
        when(store.read(anyList(), eq(PromptDto.class))).thenReturn(active);
        when(store.readOptional(anyList(), eq(PromptDto.class))).thenReturn(Optional.of(active));

        adapter.save(active);
        assertEquals(2, adapter.findAll().size());
        assertEquals(active, adapter.findById("p1").orElseThrow());
        assertEquals(active, adapter.findActive("coder", "system").orElseThrow());
        assertEquals(1, adapter.findByRole("coder").size());
        assertTrue(adapter.deleteById("p1"));
        assertTrue(adapter.deleteByRoleAndKey("coder", "system"));
        assertEquals(2, adapter.deleteAll());
        assertEquals(2, adapter.count());

        when(store.readOptional(anyList(), eq(PromptDto.class))).thenReturn(Optional.of(draft), Optional.empty());
        assertTrue(adapter.findActive("coder", "system").isEmpty());
        assertFalse(adapter.deleteById("missing"));
    }

    @Test
    void coreMemoryCreatesUpdatesReadsAndRejectsVersionConflicts() {
        FsCoreMemoryStore adapter = new FsCoreMemoryStore();
        adapter.store = store;
        when(store.readOptional(anyList(), eq(FsCoreMemoryStore.BlockMap.class)))
                .thenReturn(Optional.empty());

        adapter.upsert("user", "profile", "one", 0);
        assertThrows(VersionConflictException.class, () -> adapter.upsert("user", "profile", "bad", 2));
        assertTrue(adapter.read("user").isEmpty());
        assertTrue(adapter.get("user", "profile").isEmpty());
        adapter.delete("user", "profile");
        verify(store, atLeastOnce()).write(anyList(), any());
    }

    private static CronRunDto run(String id, String cron, String status, Instant started) {
        return new CronRunDto(
                id, cron, "session", status, started, null, null, 1, 2, 3, BigDecimal.ZERO, Instant.now());
    }

    private static PromptDto prompt(String id, String status) {
        return new PromptDto(id, "coder", "system", "content", 1, status, 100, null, Instant.now(), Instant.now());
    }
}
