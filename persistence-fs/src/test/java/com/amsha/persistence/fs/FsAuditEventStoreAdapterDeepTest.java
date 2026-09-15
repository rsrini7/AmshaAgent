package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.AuditEventDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Deep unit tests for {@link FsAuditEventStoreAdapter}.
 * Tests real adapter logic; mocks FilesystemFileStore (filesystem dependency).
 * Uses doAnswer with raw types to handle private inner classes.
 */
class FsAuditEventStoreAdapterDeepTest {

    @Mock
    FilesystemFileStore store;

    FsAuditEventStoreAdapter adapter;

    // Private inner classes accessed via reflection
    private Object sessionIndex;
    private Class<?> sessionIndexClass;
    private Class<?> indexEntryClass;

    // Tracks what mockStoreIndex should return for SessionIndex lookups
    private volatile Object currentIndex;

    @BeforeEach
    void setup() throws Exception {
        MockitoAnnotations.openMocks(this);
        adapter = new FsAuditEventStoreAdapter();
        var field = FsAuditEventStoreAdapter.class.getDeclaredField("store");
        field.setAccessible(true);
        field.set(adapter, store);

        sessionIndexClass = Class.forName("com.amsha.persistence.fs.FsAuditEventStoreAdapter$SessionIndex");
        indexEntryClass = Class.forName("com.amsha.persistence.fs.FsAuditEventStoreAdapter$IndexEntry");
        var siConstructor = sessionIndexClass.getDeclaredConstructor();
        siConstructor.setAccessible(true);
        sessionIndex = siConstructor.newInstance();
        currentIndex = null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockStoreReadOptional() {
        // Single stub that handles both SessionIndex and AuditEventDto reads
        when(store.readOptional(any(), (Class) any())).thenAnswer(inv -> {
            Class<?> clazz = inv.getArgument(1);
            if (clazz == sessionIndexClass) {
                return Optional.ofNullable(currentIndex);
            }
            // For AuditEventDto: return a default event
            return Optional.of(createEvent("session-1", 1));
        });
    }

    private void setIndex(Object index) {
        currentIndex = index;
    }

    private void clearIndex() {
        currentIndex = null;
    }

    private Object createIndexEntry(String id, String idempotencyKey, String eventType, Instant timestamp)
            throws Exception {
        // IndexEntry is a record: record IndexEntry(String id, String idempotencyKey, String eventType, Instant
        // timestamp)
        var constructor =
                indexEntryClass.getDeclaredConstructor(String.class, String.class, String.class, Instant.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, idempotencyKey, eventType, timestamp);
    }

    @SuppressWarnings("unchecked")
    private List<Object> getEvents(Object index) throws Exception {
        var eventsField = sessionIndexClass.getDeclaredField("events");
        eventsField.setAccessible(true);
        return (List<Object>) eventsField.get(index);
    }

    private void setMaxSequence(Object index, long value) throws Exception {
        var field = sessionIndexClass.getDeclaredField("maxSequence");
        field.setAccessible(true);
        field.set(index, value);
    }

    private AuditEventDto createEvent(String sessionId, long sequence) {
        return new AuditEventDto(
                UUID.randomUUID(),
                sessionId,
                "user1",
                "coordinator",
                "hash123",
                "success",
                "AGENT_COMPLETED",
                "detail",
                100,
                50,
                BigDecimal.valueOf(0.01),
                Instant.now(),
                5,
                "0.1.0",
                "idem-key-" + sequence,
                sequence,
                "hmac-hash",
                "prev-hash");
    }

    // ── save ─────────────────────────────────────────────────────────────

    @Nested
    class SaveTests {

        @Test
        void save_newSession_createsIndexAndWritesEvent() throws Exception {
            AuditEventDto dto = createEvent("session-1", 1);
            clearIndex();
            mockStoreReadOptional();

            adapter.save(dto);

            verify(store, times(2)).write(any(), any());
        }

        @Test
        void save_higherSequence_updatesIndex() throws Exception {
            AuditEventDto dto = createEvent("session-1", 5);
            setMaxSequence(sessionIndex, 3);
            setIndex(sessionIndex);
            mockStoreReadOptional();

            adapter.save(dto);

            verify(store, times(2)).write(any(), any());
        }

        @Test
        void save_equalSequence_doesNotUpdateIndex() throws Exception {
            AuditEventDto dto = createEvent("session-1", 3);
            setMaxSequence(sessionIndex, 3);
            setIndex(sessionIndex);
            mockStoreReadOptional();

            adapter.save(dto);

            verify(store, times(1)).write(any(), any());
        }

        @Test
        void save_lowerSequence_doesNotUpdateIndex() throws Exception {
            AuditEventDto dto = createEvent("session-1", 2);
            setMaxSequence(sessionIndex, 5);
            setIndex(sessionIndex);
            mockStoreReadOptional();

            adapter.save(dto);

            verify(store, times(1)).write(any(), any());
        }
    }

    // ── findLatestHash ───────────────────────────────────────────────────

    @Nested
    class FindLatestHashTests {

        @Test
        void findLatestHash_emptyIndex_returnsEmpty() {
            clearIndex();
            mockStoreReadOptional();

            assertTrue(adapter.findLatestHash("session-1").isEmpty());
        }

        @Test
        void findLatestHash_withEvents_returnsHash() throws Exception {
            UUID eventId = UUID.randomUUID();
            getEvents(sessionIndex).add(createIndexEntry(eventId.toString(), "idem", "TYPE", Instant.now()));
            setIndex(sessionIndex);
            mockStoreReadOptional();

            var result = adapter.findLatestHash("session-1");

            assertTrue(result.isPresent());
        }

        @Test
        void findLatestHash_eventFileNotFound_returnsEmpty() throws Exception {
            UUID eventId = UUID.randomUUID();
            getEvents(sessionIndex).add(createIndexEntry(eventId.toString(), "idem", "TYPE", Instant.now()));
            setIndex(sessionIndex);

            // Return empty for AuditEventDto lookups
            when(store.readOptional(any(), any())).thenAnswer(inv -> {
                Class<?> clazz = inv.getArgument(1);
                if (clazz == sessionIndexClass) {
                    return Optional.ofNullable(currentIndex);
                }
                return Optional.empty();
            });

            assertTrue(adapter.findLatestHash("session-1").isEmpty());
        }
    }

    // ── findBySessionId ──────────────────────────────────────────────────

    @Nested
    class FindBySessionIdTests {

        @Test
        void findBySessionId_emptyIndex_returnsEmptyList() {
            clearIndex();
            mockStoreReadOptional();

            assertTrue(adapter.findBySessionId("session-1").isEmpty());
        }

        @Test
        void findBySessionId_withEvents_returnsAll() throws Exception {
            getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "idem1", "TYPE", Instant.now()));
            getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "idem2", "TYPE", Instant.now()));
            setIndex(sessionIndex);
            mockStoreReadOptional();

            assertEquals(2, adapter.findBySessionId("session-1").size());
        }
    }

    // ── countByIdempotencyKey ────────────────────────────────────────────

    @Nested
    class CountByIdempotencyKeyTests {

        @Test
        void countByIdempotencyKey_noSessions_returnsZero() {
            when(store.listDirs(any())).thenReturn(List.of());

            assertEquals(0, adapter.countByIdempotencyKey("key-1"));
        }

        @Test
        void countByIdempotencyKey_matchingEvents_returnsCount() throws Exception {
            when(store.listDirs(any())).thenReturn(List.of("session-1"));
            getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "key-1", "TYPE", Instant.now()));
            getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "key-1", "TYPE", Instant.now()));
            getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "key-2", "TYPE", Instant.now()));
            setIndex(sessionIndex);
            mockStoreReadOptional();

            assertEquals(2, adapter.countByIdempotencyKey("key-1"));
        }
    }

    // ── countEventsByType ────────────────────────────────────────────────

    @Nested
    class CountEventsByTypeTests {

        @Test
        void countEventsByType_matching_returnsCount() throws Exception {
            getEvents(sessionIndex)
                    .add(createIndexEntry(UUID.randomUUID().toString(), "idem1", "TYPE_A", Instant.now()));
            getEvents(sessionIndex)
                    .add(createIndexEntry(UUID.randomUUID().toString(), "idem2", "TYPE_B", Instant.now()));
            getEvents(sessionIndex)
                    .add(createIndexEntry(UUID.randomUUID().toString(), "idem3", "TYPE_A", Instant.now()));
            setIndex(sessionIndex);
            mockStoreReadOptional();

            assertEquals(2, adapter.countEventsByType("session-1", "TYPE_A"));
        }

        @Test
        void countEventsByType_noMatch_returnsZero() throws Exception {
            getEvents(sessionIndex)
                    .add(createIndexEntry(UUID.randomUUID().toString(), "idem1", "TYPE_A", Instant.now()));
            setIndex(sessionIndex);
            mockStoreReadOptional();

            assertEquals(0, adapter.countEventsByType("session-1", "TYPE_C"));
        }
    }

    // ── getMaxSequence ───────────────────────────────────────────────────

    @Nested
    class GetMaxSequenceTests {

        @Test
        void getMaxSequence_returnsStoredMax() throws Exception {
            setMaxSequence(sessionIndex, 42);
            setIndex(sessionIndex);
            mockStoreReadOptional();

            assertEquals(42, adapter.getMaxSequence("session-1"));
        }

        @Test
        void getMaxSequence_noIndex_returnsZero() {
            clearIndex();
            mockStoreReadOptional();

            assertEquals(0, adapter.getMaxSequence("session-1"));
        }
    }

    // ── findById ─────────────────────────────────────────────────────────

    @Nested
    class FindByIdTests {

        @Test
        void findById_nullId_returnsEmpty() {
            assertTrue(adapter.findById(null).isEmpty());
        }

        @Test
        void findById_found_returnsEvent() {
            when(store.listDirs(any())).thenReturn(List.of("session-1"));
            mockStoreReadOptional();

            assertTrue(adapter.findById(UUID.randomUUID()).isPresent());
        }

        @Test
        void findById_notFound_returnsEmpty() {
            when(store.listDirs(any())).thenReturn(List.of("session-1"));
            when(store.readOptional(any(), any())).thenAnswer(inv -> {
                Class<?> clazz = inv.getArgument(1);
                if (clazz == sessionIndexClass) {
                    return Optional.ofNullable(currentIndex);
                }
                return Optional.empty();
            });

            assertTrue(adapter.findById(UUID.randomUUID()).isEmpty());
        }

        @Test
        void findById_noSessions_returnsEmpty() {
            when(store.listDirs(any())).thenReturn(List.of());

            assertTrue(adapter.findById(UUID.randomUUID()).isEmpty());
        }
    }

    // ── findAll ──────────────────────────────────────────────────────────

    @Nested
    class FindAllTests {

        @Test
        void findAll_withSessionId_returnsMatchingEvents() throws Exception {
            getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "idem1", "TYPE", Instant.now()));
            setIndex(sessionIndex);
            mockStoreReadOptional();

            assertEquals(
                    1, adapter.findAll("session-1", null, null, null, 0, 100).size());
        }

        @Test
        void findAll_appliesTheTimestampWindow() throws Exception {
            getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "idem1", "TYPE", Instant.now()));
            setIndex(sessionIndex);
            mockStoreReadOptional();
            Instant now = Instant.now();

            assertEquals(
                    1,
                    adapter.findAll("session-1", null, now.minusSeconds(60), now.plusSeconds(60), 0, 100)
                            .size(),
                    "an event inside the window survives both bounds");
            assertTrue(
                    adapter.findAll("session-1", null, now.plusSeconds(60), null, 0, 100)
                            .isEmpty(),
                    "an event before `from` is dropped");
            assertTrue(
                    adapter.findAll("session-1", null, null, now.minusSeconds(60), 0, 100)
                            .isEmpty(),
                    "an event after `to` is dropped");
        }

        @Test
        void findAll_noSessionId_scansAllSessions() throws Exception {
            when(store.listDirs(any())).thenReturn(List.of("session-1", "session-2"));
            getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "idem1", "TYPE", Instant.now()));
            setIndex(sessionIndex);
            mockStoreReadOptional();

            assertEquals(2, adapter.findAll(null, null, null, null, 0, 100).size());
        }

        @Test
        void findAll_withPagination_respectsOffsetAndLimit() throws Exception {
            Instant now = Instant.now();
            for (int i = 0; i < 10; i++) {
                getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "idem" + i, "TYPE", now));
            }
            setIndex(sessionIndex);
            mockStoreReadOptional();

            assertEquals(3, adapter.findAll("session-1", null, null, null, 2, 3).size());
        }

        @Test
        void findAll_offsetBeyondSize_returnsEmpty() throws Exception {
            Instant now = Instant.now();
            getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "idem1", "TYPE", now));
            setIndex(sessionIndex);
            mockStoreReadOptional();

            assertTrue(adapter.findAll("session-1", null, null, null, 100, 10).isEmpty());
        }

        @Test
        void findAll_nullTimestampHandledInSorting() throws Exception {
            getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "idem1", "TYPE", null));
            setIndex(sessionIndex);

            AuditEventDto event = new AuditEventDto(
                    UUID.randomUUID(),
                    "session-1",
                    "user1",
                    "coordinator",
                    "hash",
                    "success",
                    "TYPE",
                    "detail",
                    0,
                    0,
                    BigDecimal.ZERO,
                    null,
                    0,
                    "1.0",
                    "idem",
                    1,
                    "hmac",
                    "prev");
            when(store.readOptional(any(), any())).thenAnswer(inv -> {
                Class<?> clazz = inv.getArgument(1);
                if (clazz == sessionIndexClass) {
                    return Optional.ofNullable(currentIndex);
                }
                return Optional.of(event);
            });

            assertEquals(
                    1, adapter.findAll("session-1", null, null, null, 0, 100).size());
        }

        @Test
        void findAll_nullSessionIdNullIndex_returnsEmpty() {
            when(store.listDirs(any())).thenReturn(List.of("session-1"));
            clearIndex();
            mockStoreReadOptional();

            assertTrue(adapter.findAll(null, null, null, null, 0, 100).isEmpty());
        }
    }

    // ── countAll ─────────────────────────────────────────────────────────

    @Nested
    class CountAllTests {

        @Test
        void countAll_returnsSizeFromFindAll() throws Exception {
            getEvents(sessionIndex).add(createIndexEntry(UUID.randomUUID().toString(), "idem1", "TYPE", Instant.now()));
            setIndex(sessionIndex);
            mockStoreReadOptional();

            assertEquals(1, adapter.countAll("session-1", null, null, null));
        }
    }
}
