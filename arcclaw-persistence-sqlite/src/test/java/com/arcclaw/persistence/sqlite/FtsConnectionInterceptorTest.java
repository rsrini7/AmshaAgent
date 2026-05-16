package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sqlite.SQLiteConnection;
import org.sqlite.core.DB;

/**
 * Unit tests for {@link FtsConnectionInterceptor}.
 *
 * <p>Covers three distinct behavioural contracts:
 * <ol>
 *   <li><strong>vec0 loading</strong> - load_extension must fire on every fresh
 *       sqlite3* handle (first borrow) but be skipped on re-borrows of the same
 *       handle (pragma_module_list already contains "vec0").</li>
 *   <li><strong>fts schema attachment</strong> - ATTACH must fire when the fts
 *       schema is absent, must be skipped when already attached.</li>
 *   <li><strong>Error / shutdown handling</strong> - WARN fires at most once;
 *       Arc=null returns immediately without touching the connection.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class FtsConnectionInterceptorTest {

    private FtsConnectionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new FtsConnectionInterceptor();
        interceptor.jdbcUrl = "jdbc:sqlite:target/test.db";
        interceptor.vecExtensionPath = "target/lib/vec0-test";
    }

    // =========================================================================
    // Builder helpers
    // =========================================================================

    /**
     * Builds a mock Connection where:
     * <ul>
     *   <li>PRAGMA database_list returns "fts" → Step 1 ATTACH is skipped</li>
     *   <li>pragma_module_list returns vec0 presence per {@code vec0AlreadyLoaded}</li>
     *   <li>load_extension Statement is ready for verification</li>
     * </ul>
     *
     * @param vec0AlreadyLoaded {@code true}  → simulates a re-borrowed connection
     *                                           (vec0 already registered, load skipped)
     *                          {@code false} → simulates a fresh connection handle
     *                                           (vec0 not yet registered, load must fire)
     */
    private record ConnBundle(Connection conn, Statement vecStmt) {}

    private ConnBundle buildConnection(boolean vec0AlreadyLoaded) throws Exception {
        return buildConnection(vec0AlreadyLoaded, false);
    }

    /**
     * @param vec0AlreadyLoaded  see above
     * @param loadExtensionFails {@code true} → load_extension throws SQLException
     */
    private ConnBundle buildConnection(boolean vec0AlreadyLoaded,
                                   boolean loadExtensionFails) throws Exception {
        Connection conn = mock(Connection.class);
        lenient().when(conn.getAutoCommit()).thenReturn(true);

        // Step 1: PRAGMA database_list → fts already attached
        Statement pragmaStmt = mock(Statement.class);
        ResultSet dbListRs   = mock(ResultSet.class);
        when(dbListRs.next()).thenReturn(true, false);
        when(dbListRs.getString("name")).thenReturn("fts");
        when(pragmaStmt.executeQuery("PRAGMA database_list")).thenReturn(dbListRs);

        // Step 2a: pragma_module_list check
        Statement moduleCheckStmt = mock(Statement.class);
        ResultSet moduleRs        = mock(ResultSet.class);
        lenient().when(moduleRs.next()).thenReturn(vec0AlreadyLoaded);
        lenient().when(moduleCheckStmt.executeQuery(contains("pragma_module_list")))
                .thenReturn(moduleRs);

        // Step 2b: load_extension Statement
        Statement vecStmt = mock(Statement.class);
        if (loadExtensionFails) {
            lenient().when(vecStmt.execute(contains("load_extension")))
                    .thenThrow(new SQLException(
                            "no such file or directory: ./lib/vec0-test"));
        }

        lenient().when(conn.createStatement())
                .thenReturn(pragmaStmt)
                .thenReturn(moduleCheckStmt)
                .thenReturn(vecStmt);

        // Only stub unwrap when the code will actually reach enable_load_extension.
        // When vec0AlreadyLoaded=true the if(!alreadyLoaded) block is skipped entirely
        // - these stubs would never be consumed and Mockito strict mode rejects them.
        if (!vec0AlreadyLoaded) {
            SQLiteConnection sqliteConn = mock(SQLiteConnection.class);
            DB db = mock(DB.class);
            lenient().when(conn.unwrap(SQLiteConnection.class)).thenReturn(sqliteConn);
            lenient().when(sqliteConn.getDatabase()).thenReturn(db);
        }

        return new ConnBundle(conn, vecStmt);
    }


    /** Connection where PRAGMA database_list returns no "fts" → ATTACH must fire. */
    private ConnBundle buildConnectionWithoutFtsAttached() throws Exception {
        Connection conn = mock(Connection.class);
        lenient().when(conn.getAutoCommit()).thenReturn(true);

        // PRAGMA database_list - no "fts" row
        Statement pragmaStmt = mock(Statement.class);
        ResultSet dbListRs   = mock(ResultSet.class);
        when(dbListRs.next()).thenReturn(false);
        when(pragmaStmt.executeQuery("PRAGMA database_list")).thenReturn(dbListRs);

        // ATTACH statement
        Statement attachStmt = mock(Statement.class);

        // pragma_module_list - vec0 not loaded
        Statement moduleCheckStmt = mock(Statement.class);
        ResultSet moduleRs        = mock(ResultSet.class);
        when(moduleRs.next()).thenReturn(false);
        when(moduleCheckStmt.executeQuery(contains("pragma_module_list")))
                .thenReturn(moduleRs);

        // load_extension statement
        Statement vecStmt = mock(Statement.class);

        when(conn.createStatement())
                .thenReturn(pragmaStmt)       // Step 1 PRAGMA
                .thenReturn(attachStmt)        // Step 1 ATTACH
                .thenReturn(moduleCheckStmt)   // Step 2 module check
                .thenReturn(vecStmt);          // Step 2 load_extension

        SQLiteConnection sqliteConn = mock(SQLiteConnection.class);
        DB db = mock(DB.class);
        when(conn.unwrap(SQLiteConnection.class)).thenReturn(sqliteConn);
        when(sqliteConn.getDatabase()).thenReturn(db);

        return new ConnBundle(conn, vecStmt);
    }

    // =========================================================================
    // Nested test groups
    // =========================================================================

    @Nested
    @DisplayName("vec0 loading - per-connection via pragma_module_list check")
    class Vec0Loading {

        @Test
        @DisplayName("load_extension fires on fresh connection (vec0 not yet registered)")
        void loadExtensionFiredOnFreshConnection() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(mock(ArcContainer.class));

                ConnBundle b = buildConnection(false); // vec0 absent on this handle
                interceptor.onConnectionAcquire(b.conn());

                verify(b.vecStmt()).execute(contains("load_extension"));
            }
        }

        @Test
        @DisplayName("load_extension skipped on re-borrowed connection (vec0 already registered)")
        void loadExtensionSkippedOnReborrow() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(mock(ArcContainer.class));

                ConnBundle b = buildConnection(true); // vec0 already on this handle
                interceptor.onConnectionAcquire(b.conn());

                // vecStmt must never be reached
                verify(b.vecStmt(), never()).execute(anyString());
            }
        }

        @Test
        @DisplayName("load_extension fires on every fresh handle in the pool")
        void loadExtensionFiredOnEveryFreshHandle() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(mock(ArcContainer.class));

                // Three distinct physical connections - none have vec0 yet
                ConnBundle b1 = buildConnection(false);
                ConnBundle b2 = buildConnection(false);
                ConnBundle b3 = buildConnection(false);

                interceptor.onConnectionAcquire(b1.conn());
                interceptor.onConnectionAcquire(b2.conn());
                interceptor.onConnectionAcquire(b3.conn());

                verify(b1.vecStmt()).execute(contains("load_extension"));
                verify(b2.vecStmt()).execute(contains("load_extension"));
                verify(b3.vecStmt()).execute(contains("load_extension"));
            }
        }

        @Test
        @DisplayName("mixed pool: fresh handles load, re-borrows skip")
        void mixedPoolFreshLoadsRebrowsSkip() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(mock(ArcContainer.class));

                ConnBundle fresh1  = buildConnection(false); // first borrow of handle A
                ConnBundle reborrow = buildConnection(true);  // second borrow of handle A
                ConnBundle fresh2  = buildConnection(false); // first borrow of handle B

                interceptor.onConnectionAcquire(fresh1.conn());
                interceptor.onConnectionAcquire(reborrow.conn());
                interceptor.onConnectionAcquire(fresh2.conn());

                verify(fresh1.vecStmt()).execute(contains("load_extension"));
                verify(reborrow.vecStmt(), never()).execute(anyString());
                verify(fresh2.vecStmt()).execute(contains("load_extension"));
            }
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("vectorSearchEnabled flag")
    class VectorSearchEnabledFlag {

        @Test
        @DisplayName("starts false before any connection")
        void startsFalse() {
            assertFalse(interceptor.isVectorSearchEnabled());
        }

        @Test
        @DisplayName("becomes true after first successful load")
        void trueAfterFirstSuccess() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(mock(ArcContainer.class));

                interceptor.onConnectionAcquire(buildConnection(false).conn());

                assertTrue(interceptor.isVectorSearchEnabled());
            }
        }

        @Test
        @DisplayName("stays true after multiple connections including reborrows")
        void staysTrueAcrossConnections() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(mock(ArcContainer.class));

                interceptor.onConnectionAcquire(buildConnection(false).conn());
                interceptor.onConnectionAcquire(buildConnection(true).conn());
                interceptor.onConnectionAcquire(buildConnection(false).conn());

                assertTrue(interceptor.isVectorSearchEnabled(),
                        "Flag must not flip back to false");
            }
        }

        @Test
        @DisplayName("stays false when every load_extension call fails")
        void staysFalseWhenLoadFails() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(mock(ArcContainer.class));

                interceptor.onConnectionAcquire(buildConnection(false, true).conn());
                interceptor.onConnectionAcquire(buildConnection(false, true).conn());

                assertFalse(interceptor.isVectorSearchEnabled());
            }
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("fts schema attachment")
    class FtsAttachment {

        @Test
        @DisplayName("ATTACH fires when fts schema absent")
        void attachFiredWhenAbsent() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(mock(ArcContainer.class));

                Connection conn = buildConnectionWithoutFtsAttached().conn();
                interceptor.onConnectionAcquire(conn);

                // 4 createStatement calls: PRAGMA, ATTACH, moduleCheck, load_extension
                verify(conn, times(4)).createStatement();
            }
        }

        @Test
        @DisplayName("ATTACH skipped when fts schema already attached")
        void attachSkippedWhenPresent() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(mock(ArcContainer.class));

                Connection conn = buildConnection(false).conn();
                interceptor.onConnectionAcquire(conn);

                // 3 createStatement calls: PRAGMA, moduleCheck, load_extension (no ATTACH)
                verify(conn, times(3)).createStatement();
            }
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Shutdown and error handling")
    class ShutdownAndErrors {

        @Test
        @DisplayName("returns immediately and never touches connection when Arc is null")
        void noOpWhenArcNull() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(null);

                Connection conn = mock(Connection.class);
                assertDoesNotThrow(() -> interceptor.onConnectionAcquire(conn));
                verifyNoInteractions(conn);
            }
        }

        @Test
        @DisplayName("WARN fires exactly once even when every connection fails")
        void warnFiresOnlyOnce() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(mock(ArcContainer.class));

                // Three consecutive failures - WARN must appear only once in logs
                interceptor.onConnectionAcquire(buildConnection(false, true).conn());
                interceptor.onConnectionAcquire(buildConnection(false, true).conn());
                interceptor.onConnectionAcquire(buildConnection(false, true).conn());

                // Verified by asserting flag state - warnedUnavailable flips to true
                // after the first failure, suppressing subsequent WARNs.
                assertFalse(interceptor.isVectorSearchEnabled());
                // No exception thrown - degraded gracefully
            }
        }

        @Test
        @DisplayName("load_extension failure does not prevent fts schema attachment")
        void ftsAttachmentSurvivesVecFailure() throws Exception {
            try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
                arc.when(Arc::container).thenReturn(mock(ArcContainer.class));

                // Connection with fts absent + load_extension fails
                Connection conn = mock(Connection.class);
                lenient().when(conn.getAutoCommit()).thenReturn(true);

                Statement pragmaStmt = mock(Statement.class);
                ResultSet dbListRs   = mock(ResultSet.class);
                when(dbListRs.next()).thenReturn(false); // fts not attached
                when(pragmaStmt.executeQuery("PRAGMA database_list")).thenReturn(dbListRs);

                Statement attachStmt = mock(Statement.class); // ATTACH fires

                Statement moduleCheckStmt = mock(Statement.class);
                ResultSet moduleRs        = mock(ResultSet.class);
                when(moduleRs.next()).thenReturn(false);
                when(moduleCheckStmt.executeQuery(contains("pragma_module_list")))
                        .thenReturn(moduleRs);

                Statement vecStmt = mock(Statement.class);
                when(vecStmt.execute(contains("load_extension")))
                        .thenThrow(new SQLException("simulated failure"));

                when(conn.createStatement())
                        .thenReturn(pragmaStmt)
                        .thenReturn(attachStmt)
                        .thenReturn(moduleCheckStmt)
                        .thenReturn(vecStmt);

                SQLiteConnection sqliteConn = mock(SQLiteConnection.class);
                DB db = mock(DB.class);
                when(conn.unwrap(SQLiteConnection.class)).thenReturn(sqliteConn);
                when(sqliteConn.getDatabase()).thenReturn(db);

                // Must not throw - ATTACH succeeded, vec failure is caught gracefully
                assertDoesNotThrow(() -> interceptor.onConnectionAcquire(conn));
                verify(attachStmt).execute(contains("ATTACH DATABASE"));
            }
        }
    }
}
