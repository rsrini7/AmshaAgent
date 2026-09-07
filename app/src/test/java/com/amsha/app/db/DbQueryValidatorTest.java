package com.amsha.app.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DbQueryValidatorTest {

    @Test
    void rejectsEmptyOrNullQueries() {
        assertThrows(DbValidationException.class, () -> DbQueryValidator.validateAndScope(null, "ws-1", "s-1"));
        assertThrows(DbValidationException.class, () -> DbQueryValidator.validateAndScope("", "ws-1", "s-1"));
        assertThrows(DbValidationException.class, () -> DbQueryValidator.validateAndScope("   \n\t  ", "ws-1", "s-1"));
    }

    @Test
    void rejectsQueriesExceedingMaxLength() {
        String longSql = "SELECT " + "a".repeat(1005);
        DbValidationException ex = assertThrows(
                DbValidationException.class, () -> DbQueryValidator.validateAndScope(longSql, "ws-1", "s-1"));
        assertTrue(ex.getMessage().contains("maximum limit"));
    }

    @Test
    void allowsValidSingleStatementWithOptionalTrailingSemicolon() {
        var q1 = DbQueryValidator.validateAndScope("SELECT name, type FROM sqlite_master", "ws-1", "s-1");
        assertTrue(q1.sql().contains("sqlite_master"));

        var q2 = DbQueryValidator.validateAndScope("SELECT name, type FROM sqlite_master;   ", "ws-1", "s-1");
        assertTrue(q2.sql().contains("sqlite_master"));
    }

    @Test
    void stripsSingleLineAndBlockComments() {
        String sql = "SELECT -- line comment\n name /* block comment */, type FROM sqlite_master;";
        var q = DbQueryValidator.validateAndScope(sql, "ws-1", "s-1");
        assertTrue(q.sql().contains("sqlite_master"));
    }

    @Test
    void rejectsUnclosedCommentsAndLiterals() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "SELECT /* unclosed block comment FROM sqlite_master", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT 'unclosed string FROM sqlite_master", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT \"unclosed ident FROM sqlite_master", "ws-1", "s-1"));
    }

    @Test
    void rejectsMultiStatementQueries() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT 1; DROP TABLE users;", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT 1; SELECT 2;", "ws-1", "s-1"));
    }

    @Test
    void rejectsDangerousFunctions() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT load_extension('evil.so');", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT writefile('evil.txt', 'payload');", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("ATTACH DATABASE 'other.db' AS other;", "ws-1", "s-1"));
    }

    @Test
    void rejectsMutationsAndDdl() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "INSERT INTO session_catalog VALUES ('hacker');", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("UPDATE session_catalog SET role = 'admin';", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("DELETE FROM session_catalog;", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("DROP TABLE session_catalog;", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("CREATE TABLE evil (x text);", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "SELECT name INTO new_table FROM sqlite_master;", "ws-1", "s-1"));
    }

    @Test
    void rejectsRecursiveDmlUnderExplainAndCte() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "EXPLAIN INSERT INTO session_catalog VALUES (1);", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "EXPLAIN QUERY PLAN DELETE FROM session_catalog;", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "WITH x AS (INSERT INTO session_catalog VALUES(1) RETURNING *) SELECT * FROM x;",
                        "ws-1",
                        "s-1"));
    }

    @Test
    void enforcesPragmaAllowlist() {
        assertDoesNotThrow(
                () -> DbQueryValidator.validateAndScope("PRAGMA table_info('session_catalog')", "ws-1", "s-1"));
        assertDoesNotThrow(() -> DbQueryValidator.validateAndScope("PRAGMA table_list", "ws-1", "s-1"));
        assertDoesNotThrow(
                () -> DbQueryValidator.validateAndScope("PRAGMA index_list(session_catalog)", "ws-1", "s-1"));
        assertDoesNotThrow(
                () -> DbQueryValidator.validateAndScope("PRAGMA foreign_key_list(session_catalog)", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("PRAGMA database_list", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("PRAGMA compile_options", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("PRAGMA writable_schema = 1", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("PRAGMA foreign_keys = OFF", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("PRAGMA table_info(encrypted_secrets)", "ws-1", "s-1"));
    }

    @Test
    void rejectsSensitiveTables() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM encrypted_secrets;", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM api_credential;", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM llm_connection_secret_ref;", "ws-1", "s-1"));
    }

    @Test
    void rejectsDirectAccessToDataTables() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM recall_messages", "ws-1", "s-123"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM chat_memory", "ws-1", "s-123"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM archival_chunks", "ws-1", "s-123"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM session_catalog", "ws-1", "s-123"));
    }

    @Test
    void transformsParameterizedViewsWithTenantPredicates() {
        var q1 = DbQueryValidator.validateAndScope(
                "SELECT * FROM session_history_view LIMIT 20", "ws-tenant", "s-session");
        assertTrue(q1.sql().contains("recall_messages"));
        assertTrue(q1.sql().contains("workspace_id = ?"));
        assertTrue(q1.sql().contains("session_id = ?"));
        assertEquals("ws-tenant", q1.parameters().get(0));
        assertEquals("s-session", q1.parameters().get(1));

        var q2 = DbQueryValidator.validateAndScope("SELECT message FROM recent_traces_view", "ws-a", "s-b");
        assertTrue(q2.sql().contains("chat_memory"));
        assertEquals("ws-a", q2.parameters().get(0));
        assertEquals("s-b", q2.parameters().get(1));

        var q3 = DbQueryValidator.validateAndScope("SELECT content FROM memory_chunks_view", "ws-c", null);
        assertTrue(q3.sql().contains("archival_chunks"));
        assertEquals("ws-c", q3.parameters().get(0));
    }

    @Test
    void rejectsSqlColumnInSchemaMetadata() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT sql FROM sqlite_master", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT \"sql\" FROM sqlite_master", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT [sql] FROM sqlite_master", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT \"SQL\" FROM sqlite_master", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM sqlite_master", "ws-1", "s-1"));

        assertDoesNotThrow(() ->
                DbQueryValidator.validateAndScope("SELECT name, type, tbl_name FROM sqlite_master", "ws-1", "s-1"));
    }

    @Test
    void allowsLegitimateSubstringsWithoutFalsePositives() {
        assertDoesNotThrow(() -> DbQueryValidator.validateAndScope(
                "SELECT name, type FROM sqlite_master WHERE tbl_name = 'attachment'", "ws-1", "s-1"));
        assertDoesNotThrow(() -> DbQueryValidator.validateAndScope(
                "SELECT name, type FROM sqlite_master WHERE tbl_name = 'edited_at'", "ws-1", "s-1"));
    }

    @Test
    void rejectsAllowlistedViewInSubqueryOrCteOrJoin() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "SELECT * FROM (SELECT * FROM session_history_view)", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "WITH v AS (SELECT * FROM session_history_view) SELECT * FROM v", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "SELECT * FROM session_history_view JOIN recent_traces_view ON 1=1", "ws-1", "s-1"));
    }

    @Test
    void rejectsFunctionsAndClausesReferencingSqlColumnInSchemaMetadata() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT group_concat(sql) FROM sqlite_master", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT substr(sql, 1, 10) FROM sqlite_master", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "SELECT name FROM sqlite_master WHERE sql LIKE '%secret%'", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT name FROM sqlite_master ORDER BY sql", "ws-1", "s-1"));

        assertDoesNotThrow(() -> DbQueryValidator.validateAndScope(
                "SELECT name, type, tbl_name, rootpage FROM sqlite_master", "ws-1", "s-1"));
    }

    @Test
    void failsClosedWhenWorkspaceOrSessionMissingForParameterizedViews() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM session_history_view", null, "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM session_history_view", "", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM session_history_view", "ws-1", null));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM session_history_view", "ws-1", ""));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM recent_traces_view", "ws-1", null));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT * FROM memory_chunks_view", null, null));
    }

    @Test
    void rejectsScalarSubqueryAndOracleBypassesOnSchemaMetadata() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "SELECT (SELECT sql FROM sqlite_master LIMIT 1) AS leaked", "ws-1", "s-1"));

        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope(
                        "SELECT name FROM sqlite_master WHERE name IN (SELECT sql FROM sqlite_master)", "ws-1", "s-1"));
    }

    @Test
    void preservesCallerTableAliasOnParameterizedViews() {
        DbQueryValidator.ValidatedQuery res = DbQueryValidator.validateAndScope(
                "SELECT * FROM session_history_view sh WHERE sh.role = 'user'", "ws-1", "s-1");
        assertTrue(res.sql().contains("AS sh"));
        assertEquals("session_history_view", res.targetView());
    }

    @Test
    void rejectsUserJdbcPlaceholdersToPreventBindingShift() {
        assertThrows(
                DbValidationException.class,
                () -> DbQueryValidator.validateAndScope("SELECT ? AS a, * FROM session_history_view", "ws-1", "s-1"));
    }
}
