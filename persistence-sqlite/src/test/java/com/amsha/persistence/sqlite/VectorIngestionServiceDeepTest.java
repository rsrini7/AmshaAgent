package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.amsha.persistence.sqlite.jdbi.TestJdbiArguments;

import org.jdbi.v3.core.ConnectionFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.Function;

/**
 * Real-database coverage for {@link VectorIngestionService}.
 *
 * <p>The existing {@code VectorIngestionServiceTest} mocks {@code Jdbi}, so the {@code useHandle}
 * / {@code withHandle} lambdas never run and none of the service's SQL executes — 23 uncovered
 * lines. Here the database is real (a temp SQLite file) and the service's own
 * {@code float[]} argument factory is registered through
 * {@link TestJdbiArguments#floatArray()}, so the little-endian blob encoding is exercised too.
 *
 * <p>{@code vec0} is not wired into this module's tests, so {@code vec_archival} is created as a
 * plain table and {@code vec_distance_cosine} is registered as a shim that returns the stored
 * vector's first component as the distance. That makes ordering and limiting verifiable rather
 * than incidental.
 */
class VectorIngestionServiceDeepTest {

    @TempDir
    Path tempDir;

    private Jdbi jdbi;
    private VectorIngestionService service;

    @BeforeEach
    void setUp() {
        Path database = tempDir.resolve("vec.db");
        ConnectionFactory connectionFactory = () -> {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
            registerDistanceShim(connection);
            return connection;
        };

        jdbi = Jdbi.create(connectionFactory).registerArgument(TestJdbiArguments.floatArray());
        jdbi.useHandle(handle ->
                handle.execute("CREATE TABLE vec_archival (chunk_id TEXT PRIMARY KEY, embedding BLOB NOT NULL)"));

        service = new VectorIngestionService();
        service.jdbi = jdbi;
        service.ftsInterceptor = mock(FtsConnectionInterceptor.class);
        service.vectorIndexMetadata = mock(VectorIndexMetadataPort.class);
        service.embedDimensions = 1;
    }

    /** Makes {@code vec_distance_cosine} echo the stored vector's first component as the distance. */
    private static void registerDistanceShim(Connection connection) throws SQLException {
        Function.create(connection, "vec_distance_cosine", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                result(ByteBuffer.wrap(value_blob(0))
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getFloat(0));
            }
        });
    }

    private static float[] vector(float first) {
        return new float[] {first};
    }

    @Test
    void ingestVectorStoresTheEmbeddingAndCompletes() {
        service.ingestVector("chunk-a", vector(0.25f)).join();

        List<Object[]> rows = service.searchVector(vector(0.0f), 10);

        assertEquals(1, rows.size());
        assertEquals("chunk-a", rows.getFirst()[0]);
        assertEquals(0.25d, (double) rows.getFirst()[1], 1e-6);
    }

    @Test
    void ingestVectorReplacesAnExistingEmbeddingForTheSameChunk() {
        service.ingestVector("chunk-a", vector(0.9f)).join();
        service.ingestVector("chunk-a", vector(0.1f)).join();

        List<Object[]> rows = service.searchVector(vector(0.0f), 10);

        assertEquals(1, rows.size());
        assertEquals(0.1d, (double) rows.getFirst()[1], 1e-6);
    }

    @Test
    void searchVectorOrdersByAscendingDistanceAndHonoursTheLimit() {
        service.ingestVector("far", vector(0.9f)).join();
        service.ingestVector("near", vector(0.1f)).join();

        List<Object[]> all = service.searchVector(vector(0.0f), 10);
        assertEquals(2, all.size());
        assertEquals("near", all.get(0)[0]);
        assertEquals("far", all.get(1)[0]);

        List<Object[]> limited = service.searchVector(vector(0.0f), 1);
        assertEquals(1, limited.size());
        assertEquals("near", limited.getFirst()[0]);
    }

    @Test
    void searchVectorReturnsEmptyWhenNothingIsStored() {
        assertTrue(service.searchVector(vector(0.0f), 10).isEmpty());
    }

    @Test
    void deleteVectorRemovesTheEmbedding() {
        service.ingestVector("chunk-a", vector(0.1f)).join();

        service.deleteVector("chunk-a").join();

        assertTrue(service.searchVector(vector(0.0f), 10).isEmpty());
    }

    @Test
    void deleteVectorLeavesOtherChunksAlone() {
        service.ingestVector("kept", vector(0.1f)).join();
        service.ingestVector("removed", vector(0.2f)).join();

        service.deleteVector("removed").join();

        List<Object[]> rows = service.searchVector(vector(0.0f), 10);
        assertEquals(1, rows.size());
        assertEquals("kept", rows.getFirst()[0]);
    }

    @Test
    void deleteVectorOnAMissingChunkIsANoOp() {
        service.ingestVector("kept", vector(0.1f)).join();

        assertDoesNotThrow(() -> service.deleteVector("absent").join());

        assertEquals(1, service.searchVector(vector(0.0f), 10).size());
    }

    @Test
    void ingestVectorSwallowsAWriteFailureInsteadOfFailingTheFuture() {
        Jdbi broken = mock(Jdbi.class);
        doAnswer(invocation -> {
                    throw new IllegalStateException("disk full");
                })
                .when(broken)
                .useHandle(any());
        service.jdbi = broken;

        assertDoesNotThrow(() -> service.ingestVector("chunk-a", vector(0.1f)).join());
    }

    @Test
    void deleteVectorSwallowsAWriteFailureInsteadOfFailingTheFuture() {
        Jdbi broken = mock(Jdbi.class);
        doAnswer(invocation -> {
                    throw new IllegalStateException("disk full");
                })
                .when(broken)
                .useHandle(any());
        service.jdbi = broken;

        assertDoesNotThrow(() -> service.deleteVector("chunk-a").join());
    }

    // ---------- shutdown() ----------

    @Test
    void shutdownRestoresTheInterruptFlagWhenInterruptedWhileDraining() {
        // The writer must still be busy when shutdown() runs. On an idle executor awaitTermination()
        // returns true straight away without ever consulting the interrupt status, so the catch arm is
        // skipped and the assertion below would be satisfied by this test's own interrupt() call.
        VectorIngestionService busy = new VectorIngestionService();
        Jdbi slowJdbi = mock(Jdbi.class);
        doAnswer(invocation -> {
                    Thread.sleep(10_000);
                    return null;
                })
                .when(slowJdbi)
                .useHandle(any());
        busy.jdbi = slowJdbi;

        busy.ingestVector("chunk-a", vector(0.1f));

        long startNanos = System.nanoTime();
        Thread.currentThread().interrupt();
        try {
            // A pre-interrupted thread makes awaitTermination throw immediately rather than wait out its
            // 5s window.
            assertDoesNotThrow(busy::shutdown);
            assertTrue(Thread.currentThread().isInterrupted(), "shutdown must restore the interrupt flag");
        } finally {
            Thread.interrupted();
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        // Proves the interrupt arm ran rather than the 5s timeout arm.
        assertTrue(
                elapsedMillis < 5_000,
                "an interrupted drain must return immediately, not wait out the window; took " + elapsedMillis + "ms");
    }

    @Test
    void shutdownForcesTerminationWhenTheWriterDoesNotDrain() {
        VectorIngestionService blocked = new VectorIngestionService();
        Jdbi slowJdbi = mock(Jdbi.class);
        doAnswer(invocation -> {
                    Thread.sleep(6000);
                    return null;
                })
                .when(slowJdbi)
                .useHandle(any());
        blocked.jdbi = slowJdbi;

        blocked.ingestVector("chunk-a", vector(0.1f));

        long startNanos = System.nanoTime();
        blocked.shutdown();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        // The single writer is still busy, so shutdown must wait out its 5s window then force it.
        assertTrue(elapsedMillis >= 5_000, "shutdown should wait the full drain window, took " + elapsedMillis + "ms");
        assertTrue(elapsedMillis < 30_000, "shutdown should not hang indefinitely, took " + elapsedMillis + "ms");
    }

    @Test
    void isVectorAvailableRequiresBothFlags() {
        when(service.ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(service.vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.ARCHIVAL))
                .thenReturn(true);
        assertTrue(service.isVectorAvailable());

        when(service.vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.ARCHIVAL))
                .thenReturn(false);
        assertTrue(!service.isVectorAvailable());
    }
}
