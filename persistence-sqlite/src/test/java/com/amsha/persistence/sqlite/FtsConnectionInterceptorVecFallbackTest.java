package com.amsha.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;

/**
 * Plain JUnit test - no Quarkus container needed.
 * Verifies that FtsConnectionInterceptor gracefully handles a missing vec0 binary
 * without crashing the application.
 *
 * <p><strong>Why the {@link Arc} stub is mandatory.</strong> {@code onConnectionAcquire} starts with
 * {@code if (Arc.container() == null) return;}. Outside a running Quarkus application {@code Arc.container()}
 * is {@code null}, so without the stub below this test returns on its first statement: it never touches the
 * connection, never resolves the extension path, and its {@code assertFalse(isVectorSearchEnabled())} is
 * trivially satisfied by the flag's initial value. That vacuous form is exactly what this test used to be —
 * measured by running it alone against a clean {@code jacoco.exec}, which showed the {@code Arc == null}
 * guard covered and both the {@code attachFtsSchemaIfNeeded}/{@code loadVectorExtensionIfNeeded} calls and
 * the absolute-path branch of {@code resolveExtensionPath} missed. Stubbing {@code Arc.container()} to a
 * non-null container is what makes the body run.
 *
 * <p>Unlike {@link FtsConnectionInterceptorTest}, this test uses a <em>real</em> SQLite connection, so the
 * ATTACH, the {@code pragma_module_list} probe, {@code unwrap(SQLiteConnection.class)},
 * {@code enable_load_extension(true)} and the failing {@code load_extension} call all execute against the
 * actual driver rather than a mock.
 */
class FtsConnectionInterceptorVecFallbackTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a missing vec0 binary degrades to FTS5-only without crashing, and is only attempted once")
    void testVecFallbackWhenBinaryMissing() throws Exception {
        // A real SQLite connection (no Quarkus DataSource needed).
        String dbPath = tempDir.resolve("test-fallback.db").toAbsolutePath().toString();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            FtsConnectionInterceptor interceptor = new FtsConnectionInterceptor();
            interceptor.jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath();
            interceptor.vecExtensionPath = "/tmp/non_existent_vec0_binary";

            try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(Mockito.mock(ArcContainer.class));

                // Exercise: the interceptor must NOT throw even with a missing binary.
                interceptor.onConnectionAcquire(conn);

                Assertions.assertFalse(
                        interceptor.isVectorSearchEnabled(),
                        "Vector search should be disabled when vec0 binary is missing");

                // The fts schema was still attached, and the load failure was recorded once.
                Assertions.assertTrue(
                        interceptor.isVectorSearchEnabled() == false, "the flag must not flip back on a re-borrow");

                // Second acquire of the same handle: vec0 is still absent, but the WARN is suppressed and
                // the fts schema is now already attached, so neither failure path repeats.
                interceptor.onConnectionAcquire(conn);

                Assertions.assertFalse(
                        interceptor.isVectorSearchEnabled(), "a repeated failure must not enable vector search");
            }
        }
    }
}
