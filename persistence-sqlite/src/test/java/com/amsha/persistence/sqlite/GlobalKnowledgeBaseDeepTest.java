package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.jdbi.v3.core.ConnectionFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.Function;

/**
 * Real-database coverage for {@link GlobalKnowledgeBase}.
 *
 * <p>The existing {@code GlobalKnowledgeBaseTest} mocks {@code Jdbi} entirely, so it can only
 * reach the three argument guards and {@code floatArrayToJson} — the whole ingest transaction
 * and the whole search query (42 lines) never execute.
 *
 * <p>Here the database is real: a temp SQLite file with an attached {@code fts} database (the
 * code writes to {@code fts.vec_global}, which SQLite resolves as an attached schema). The only
 * substitution is a registered {@code vec_distance_cosine} shim, because the {@code vec0}
 * native extension is not wired into this module's tests. The shim derives the distance from the
 * stored embedding text, which the mocked {@link EmbeddingModel} controls — so both the
 * {@code ORDER BY distance ASC} ordering and the {@code 1.0 - distance} scoring are verified
 * rather than assumed.
 */
class GlobalKnowledgeBaseDeepTest {

    @TempDir
    Path tempDir;

    private Jdbi jdbi;
    private GlobalKnowledgeBase knowledgeBase;
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() throws Exception {
        Path mainDb = tempDir.resolve("global.db");
        Path ftsDb = tempDir.resolve("fts.db");

        ConnectionFactory connectionFactory = () -> {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mainDb.toAbsolutePath());
            registerDistanceShim(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ATTACH DATABASE '" + ftsDb.toAbsolutePath() + "' AS fts");
            }
            return connection;
        };

        jdbi = Jdbi.create(connectionFactory);
        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE fts.vec_global (chunk_id TEXT PRIMARY KEY, embedding TEXT NOT NULL)");
            handle.execute(
                    "CREATE TABLE rag_chunk (" + "chunk_id TEXT PRIMARY KEY, text TEXT, source TEXT, created_at TEXT)");
        });

        embeddingModel = mock(EmbeddingModel.class);
        FtsConnectionInterceptor ftsInterceptor = mock(FtsConnectionInterceptor.class);
        when(ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        VectorIndexMetadataPort vectorIndexMetadata = mock(VectorIndexMetadataPort.class);
        when(vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL))
                .thenReturn(true);

        knowledgeBase = new GlobalKnowledgeBase();
        knowledgeBase.jdbi = jdbi;
        knowledgeBase.embeddingModel = embeddingModel;
        knowledgeBase.ftsInterceptor = ftsInterceptor;
        knowledgeBase.vectorIndexMetadata = vectorIndexMetadata;
        knowledgeBase.embedDimensions = 1;
    }

    /** Makes {@code vec_distance_cosine} echo the stored embedding's first component as the distance. */
    private static void registerDistanceShim(Connection connection) throws SQLException {
        Function.create(connection, "vec_distance_cosine", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                String stored = value_text(0).replace("[", "").replace("]", "");
                result(Double.parseDouble(stored.split(",")[0]));
            }
        });
    }

    private void embed(String text, float value) {
        when(embeddingModel.embed(text)).thenReturn(Response.from(Embedding.from(new float[] {value})));
    }

    @Test
    void ingestWritesBothTheVectorAndTheChunkRow() {
        embed("some text", 0.5f);

        String chunkId = knowledgeBase.ingest("some text");

        long vectorRows = jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM fts.vec_global")
                .mapTo(Long.class)
                .one());
        String storedText = jdbi.withHandle(handle -> handle.createQuery("SELECT text FROM rag_chunk")
                .mapTo(String.class)
                .one());
        String storedChunkId = jdbi.withHandle(handle -> handle.createQuery("SELECT chunk_id FROM rag_chunk")
                .mapTo(String.class)
                .one());
        String storedSource = jdbi.withHandle(handle -> handle.createQuery("SELECT source FROM rag_chunk")
                .mapTo(String.class)
                .one());

        assertEquals(1L, vectorRows);
        assertEquals("some text", storedText);
        assertEquals(chunkId, storedChunkId);
        assertEquals("System", storedSource);
    }

    @Test
    void ingestReturnsNullWhenTheChunkInsertFails() {
        embed("some text", 0.5f);
        jdbi.useHandle(handle -> handle.execute("DROP TABLE rag_chunk"));

        // The vector insert succeeds but the chunk insert does not, so the transaction fails and
        // ingest must swallow the error and report failure rather than propagate.
        assertNull(knowledgeBase.ingest("some text"));
    }

    @Test
    void searchOrdersByAscendingDistanceAndScoresAsOneMinusDistance() {
        embed("near", 0.1f);
        embed("far", 0.9f);
        embed("query", 0.0f);
        knowledgeBase.ingest("near");
        knowledgeBase.ingest("far");

        List<MemoryChunkDto> results = knowledgeBase.search("query", 10);

        assertEquals(2, results.size());
        assertEquals("near", results.get(0).text(), "the smaller distance must rank first");
        assertEquals("far", results.get(1).text());
        assertEquals(0.9, results.get(0).score(), 1e-9);
        assertEquals(0.1, results.get(1).score(), 1e-9);
    }

    @Test
    void searchAppliesTopNAsTheQueryLimit() {
        embed("near", 0.1f);
        embed("far", 0.9f);
        embed("query", 0.0f);
        knowledgeBase.ingest("near");
        knowledgeBase.ingest("far");

        List<MemoryChunkDto> results = knowledgeBase.search("query", 1);

        assertEquals(1, results.size());
        assertEquals("near", results.get(0).text());
    }

    @Test
    void searchFallsBackToEmptyTextWhenTheChunkRowIsGone() {
        embed("orphaned", 0.3f);
        embed("query", 0.0f);
        knowledgeBase.ingest("orphaned");
        jdbi.useHandle(handle -> handle.execute("DELETE FROM rag_chunk"));

        List<MemoryChunkDto> results = knowledgeBase.search("query", 10);

        // The vector row survives, so the chunk is still returned — with an empty text fallback.
        assertEquals(1, results.size());
        assertEquals("", results.get(0).text());
    }

    @Test
    void searchReturnsEmptyWhenTheVectorQueryFails() {
        embed("query", 0.0f);
        jdbi.useHandle(handle -> handle.execute("DROP TABLE fts.vec_global"));

        assertTrue(knowledgeBase.search("query", 10).isEmpty());
    }

    @Test
    void ingestReturnsNullWhenTheEmbeddingModelFails() {
        when(embeddingModel.embed(any(String.class))).thenThrow(new IllegalStateException("provider down"));

        assertNull(knowledgeBase.ingest("some text"));
    }
}
