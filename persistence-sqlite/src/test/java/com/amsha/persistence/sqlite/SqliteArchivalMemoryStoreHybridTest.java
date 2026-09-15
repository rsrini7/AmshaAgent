package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalSearchQuery;
import com.amsha.persistence.sqlite.jdbi.ArchivalMemoryJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain-unit-test companion to {@link SqliteArchivalMemoryStoreTest}.
 *
 * <p>The existing {@code @QuarkusTest} is real-database and end-to-end, but it only ever
 * passes {@code useVector=false}, so {@code search()} short-circuits at the BM25 branch and
 * <em>neither</em> {@code hybridSearch} nor {@code mergeRrf} has ever executed — 49 missed
 * lines alongside a green test class.
 *
 * <p>Here {@link Jdbi} and {@link VectorIngestionService} are mocked so the hybrid BM25+vector
 * path, its two BM25 fallbacks, its exception fallback and every RRF merge arm can be driven
 * deterministically without a database or the sqlite-vec extension. The DAO itself is mocked
 * (it is a JDBI SQL-object interface), which is sound here because this test's subject is the
 * store's own ranking/merging logic, not the SQL — the SQL is covered by
 * {@link SqliteArchivalMemoryStoreTest} and {@link SqliteArchivalSearchByTypeTest}.
 */
class SqliteArchivalMemoryStoreHybridTest {

    private static final String USER = "user-hybrid";
    private static final float[] EMBEDDING = {0.1f, 0.2f, 0.3f};

    private Jdbi jdbi;
    private ArchivalMemoryJdbiDao dao;
    private VectorIngestionService vectorIngestion;
    private SqliteArchivalMemoryStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbi = mock(Jdbi.class);
        dao = mock(ArchivalMemoryJdbiDao.class);
        vectorIngestion = mock(VectorIngestionService.class);

        // Route both Jdbi extension styles at the mocked DAO, so the lambdas the store passes
        // in actually run against it rather than being silently skipped.
        when(jdbi.<Object, ArchivalMemoryJdbiDao, RuntimeException>withExtension(
                        eq(ArchivalMemoryJdbiDao.class), any()))
                .thenAnswer(invocation -> ((ExtensionCallback<Object, ArchivalMemoryJdbiDao, RuntimeException>)
                                invocation.getArgument(1))
                        .withExtension(dao));
        doAnswer(invocation -> {
                    ((ExtensionConsumer<ArchivalMemoryJdbiDao, RuntimeException>) invocation.getArgument(1))
                            .useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .<ArchivalMemoryJdbiDao, RuntimeException>useExtension(eq(ArchivalMemoryJdbiDao.class), any());

        store = new SqliteArchivalMemoryStore(jdbi);
        store.vectorIngestion = vectorIngestion;
    }

    // ---------- hybrid path: the two BM25 fallbacks ----------

    @Test
    void hybridSearchFallsBackToBm25WhenTheQueryCarriesNoEmbedding() {
        when(vectorIngestion.isVectorAvailable()).thenReturn(true);
        when(dao.searchAllTypes(eq(USER), anyString(), anyInt()))
                .thenReturn(List.of(chunk("a"), chunk("b"), chunk("c")));

        // useVector=true with a null embedding and null chunkTypes: enters hybridSearch, then
        // takes the no-embedding fallback, and bm25Search takes its chunkTypesEmpty arm.
        List<ArchivalChunkDto> results =
                store.search(new ArchivalSearchQuery(USER, "content", 2, null, true, true, null));

        assertEquals(List.of("a", "b"), ids(results), "the caller's limit is applied to the BM25 rows");
        verify(vectorIngestion, never()).searchVector(any(), anyInt());
    }

    @Test
    void hybridSearchFallsBackToBm25WhenTheVectorIndexReturnsNoRows() {
        when(vectorIngestion.isVectorAvailable()).thenReturn(true);
        when(dao.searchByTypes(eq(USER), anyString(), anyInt(), any())).thenReturn(List.of(chunk("a"), chunk("b")));
        when(vectorIngestion.searchVector(any(), anyInt())).thenReturn(List.of());

        List<ArchivalChunkDto> results =
                store.search(new ArchivalSearchQuery(USER, "content", 5, Set.of("DOCUMENT"), true, true, EMBEDDING));

        assertEquals(List.of("a", "b"), ids(results));
        verify(dao, never()).getByIds(any());
    }

    @Test
    void hybridSearchFallsBackToBm25WhenTheVectorPathThrows() {
        when(vectorIngestion.isVectorAvailable()).thenReturn(true);
        when(dao.searchAllTypes(eq(USER), anyString(), anyInt())).thenReturn(List.of(chunk("a")));
        when(vectorIngestion.searchVector(any(), anyInt())).thenThrow(new IllegalStateException("vec0 unavailable"));

        List<ArchivalChunkDto> results =
                store.search(new ArchivalSearchQuery(USER, "content", 5, null, true, true, EMBEDDING));

        assertEquals(List.of("a"), ids(results), "a failing vector path must not fail the search");
    }

    // ---------- hybrid path: the RRF merge ----------

    @Test
    void hybridSearchMergesBm25AndVectorRanksWithRrf() {
        when(vectorIngestion.isVectorAvailable()).thenReturn(true);
        // searchLimit = max(limit * 2, 20) = max(20, 20) = 20 — both searches are oversampled.
        when(dao.searchAllTypes(eq(USER), anyString(), eq(20))).thenReturn(List.of(chunk("a"), chunk("b")));
        when(vectorIngestion.searchVector(any(), eq(20)))
                .thenReturn(List.of(new Object[] {"b", 0.10d}, new Object[] {"c", 0.20d}));
        when(dao.getByIds(List.of("b", "c"))).thenReturn(List.of(chunk("b"), chunk("c")));

        List<ArchivalChunkDto> results =
                store.search(new ArchivalSearchQuery(USER, "content", 10, null, true, true, EMBEDDING));

        // RRF (k=60): b = 1/62 + 1/61 (in both lists), a = 1/61 (BM25 only), c = 1/62 (vector only).
        assertEquals(List.of("b", "a", "c"), ids(results));
    }

    @Test
    void hybridSearchPreservesTheVectorRankOrderWhenBuildingTheChunkList() {
        when(vectorIngestion.isVectorAvailable()).thenReturn(true);
        when(dao.searchAllTypes(eq(USER), anyString(), anyInt())).thenReturn(List.of());
        // The DAO returns the batch in an order unrelated to the vector ranking.
        when(vectorIngestion.searchVector(any(), anyInt()))
                .thenReturn(List.of(new Object[] {"third", 0.1d}, new Object[] {"first", 0.2d}));
        when(dao.getByIds(List.of("third", "first"))).thenReturn(List.of(chunk("first"), chunk("third")));

        List<ArchivalChunkDto> results =
                store.search(new ArchivalSearchQuery(USER, "content", 10, null, true, true, EMBEDDING));

        // The store must re-order by vector rank, not by the DAO's batch order.
        assertEquals(List.of("third", "first"), ids(results));
    }

    // ---------- mergeRrf as a pure function ----------

    @Test
    void mergeRrfScoresAChunkAppearingInBothListsAboveEitherListAlone() {
        List<ArchivalChunkDto> merged = SqliteArchivalMemoryStore.mergeRrf(
                List.of(chunk("a"), chunk("b")), List.of(chunk("b"), chunk("c")), 10);

        assertEquals(List.of("b", "a", "c"), ids(merged));
    }

    @Test
    void mergeRrfAppliesTheLimitAfterSorting() {
        List<ArchivalChunkDto> merged =
                SqliteArchivalMemoryStore.mergeRrf(List.of(chunk("a"), chunk("b"), chunk("c")), List.of(), 2);

        assertEquals(List.of("a", "b"), ids(merged));
    }

    @Test
    void mergeRrfUsesTheVectorListWhenBm25IsEmpty() {
        List<ArchivalChunkDto> merged =
                SqliteArchivalMemoryStore.mergeRrf(List.of(), List.of(chunk("v1"), chunk("v2")), 10);

        assertEquals(List.of("v1", "v2"), ids(merged));
    }

    @Test
    void mergeRrfWithBothListsEmptyReturnsEmpty() {
        assertTrue(SqliteArchivalMemoryStore.mergeRrf(List.of(), List.of(), 10).isEmpty());
    }

    // ---------- remaining uncovered delegation ----------

    @Test
    void listByUserIdDelegatesToTheDao() {
        when(dao.listByUserId(USER, 7, 3)).thenReturn(List.of(chunk("a")));

        assertEquals(List.of("a"), ids(store.listByUserId(USER, 7, 3)));
    }

    @Test
    void anEmptyChunkTypeSetTakesTheSameArmAsANullOne() {
        when(vectorIngestion.isVectorAvailable()).thenReturn(false);
        when(dao.searchAllTypes(eq(USER), anyString(), anyInt())).thenReturn(List.of(chunk("a")));

        // useVector=false short-circuits before hybridSearch; Set.of() must behave like null.
        List<ArchivalChunkDto> results =
                store.search(new ArchivalSearchQuery(USER, "content", 5, Set.of(), false, true, null));

        assertEquals(List.of("a"), ids(results));
    }

    // ---------- helpers ----------

    private static List<String> ids(List<ArchivalChunkDto> chunks) {
        return chunks.stream().map(ArchivalChunkDto::chunkId).toList();
    }

    private static ArchivalChunkDto chunk(String chunkId) {
        Instant now = Instant.now();
        return new ArchivalChunkDto(
                chunkId, USER, "content of " + chunkId, "DOCUMENT", "session-1", "msg-1", "tag1", 0.9, 0, now, now);
    }
}
