package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.amsha.persistence.api.AuditEventDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FsAuditEventStoreAdapterTest {

    @TempDir
    File tempDir;

    private FsAuditEventStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new FsAuditEventStoreAdapter();
        FsStoreProperties props = mock(FsStoreProperties.class);
        when(props.dataDir()).thenReturn(tempDir.getAbsolutePath());
        FilesystemFileStore store = new FilesystemFileStore(props);
        adapter.store = store;
    }

    @Test
    void testSaveAndFind() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        AuditEventDto dto = new AuditEventDto(
                id,
                "sess-1",
                "usr-1",
                "coder",
                "hash1",
                "FINISH",
                "LOOP_FINISH",
                "ok",
                100,
                50,
                new BigDecimal("0.01"),
                now,
                5,
                "1.0",
                "idem-1",
                1L,
                "curr-hash",
                "prev-hash");

        adapter.save(dto);

        assertEquals(1L, adapter.getMaxSequence("sess-1"));
        assertEquals(1, adapter.countEventsByType("sess-1", "LOOP_FINISH"));
        assertEquals(1, adapter.countByIdempotencyKey("idem-1"));
        assertTrue(adapter.findLatestHash("sess-1").isPresent());
        assertEquals("curr-hash", adapter.findLatestHash("sess-1").get());

        var list = adapter.findBySessionId("sess-1");
        assertEquals(1, list.size());
        assertEquals(id, list.get(0).id());

        var foundById = adapter.findById(id);
        assertTrue(foundById.isPresent());

        var all = adapter.findAll("sess-1", "LOOP_FINISH", null, null, 0, 10);
        assertEquals(1, all.size());
    }
}
