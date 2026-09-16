package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.sqlite.jdbi.CompactionJdbiDao;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Plain JUnit coverage for {@link SqliteCompactionStoreAdapter}'s two best-effort FTS arms.
 *
 * <p>Why a separate class: {@link SqliteCompactionStoreAdapterTest} is a {@code @QuarkusTest} against a
 * database where the {@code fts.compaction_summary_fts} table always exists, so the FTS index/delete
 * failures those arms tolerate can never be produced there. Mocking the DAO is the only way to make them
 * fire — and unlike a real driver, it also pins that the failure is swallowed rather than propagated.
 */
class SqliteCompactionStoreAdapterFtsFailureTest {

    private Jdbi jdbi;
    private Handle handle;
    private CompactionJdbiDao dao;
    private SqliteCompactionStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        handle = mock(Handle.class);
        dao = mock(CompactionJdbiDao.class);
        adapter = new SqliteCompactionStoreAdapter(jdbi);

        when(handle.attach(CompactionJdbiDao.class)).thenReturn(dao);
        // Jdbi.useTransaction(HandleConsumer) is void, so it needs the doAnswer(...).when(...) form.
        doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    HandleConsumer<RuntimeException> consumer = invocation.getArgument(0);
                    consumer.useHandle(handle);
                    return null;
                })
                .when(jdbi)
                .useTransaction(any());
    }

    private static CompactionSummaryDto summary(String sessionId) {
        return new CompactionSummaryDto(sessionId, "compacted summary", Instant.now(), 4, "user-1");
    }

    @Test
    @DisplayName("a failing FTS index write is logged and swallowed; the relational upsert still commits")
    void ftsIndexFailureIsSwallowed() {
        doThrow(new RuntimeException("no such table: fts.compaction_summary_fts"))
                .when(dao)
                .upsertFts(anyString(), anyString());

        CompactionSummaryDto dto = summary("session-1");

        assertDoesNotThrow(() -> adapter.upsert(dto), "a missing FTS index must not fail the compaction write itself");

        InOrder order = inOrder(dao);
        order.verify(dao)
                .upsert(dto.sessionId(), dto.summary(), dto.compactedAt(), dto.messagesCompacted(), dto.userId());
        order.verify(dao).deleteFtsBySessionId(dto.sessionId());
        order.verify(dao).upsertFts(dto.sessionId(), dto.summary());
    }

    @Test
    @DisplayName("a failing FTS delete is logged and swallowed; the relational delete still commits")
    void ftsDeleteFailureIsSwallowed() {
        doThrow(new RuntimeException("no such table: fts.compaction_summary_fts"))
                .when(dao)
                .deleteFtsBySessionId(anyString());

        assertDoesNotThrow(() -> adapter.deleteBySessionId("session-1"));

        verify(dao).deleteBySessionId("session-1");
        verify(dao).deleteFtsBySessionId("session-1");
    }

    @Test
    @DisplayName("a healthy FTS sync deletes the stale row before writing the new summary")
    void healthyFtsSyncReplacesTheIndexedRow() {
        CompactionSummaryDto dto = summary("session-1");

        adapter.upsert(dto);

        InOrder order = inOrder(dao);
        order.verify(dao).deleteFtsBySessionId(dto.sessionId());
        order.verify(dao).upsertFts(dto.sessionId(), dto.summary());
    }
}
