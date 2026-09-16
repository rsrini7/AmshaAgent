package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

import com.amsha.app.api.DbResultMapper.DbQueryResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for {@link DbResultMapper}, the redaction layer in front of the DB console.
 *
 * <p>The redaction predicate is the security boundary here: a column it fails to flag is a column whose
 * values are returned verbatim to the console. Both the exact-name list and each substring rule therefore
 * get their own case, including the two {@code token} exemptions.
 */
class DbResultMapperTest {

    @Test
    @DisplayName("a null column name is not sensitive")
    void nullColumnNameIsNotSensitive() {
        assertFalse(DbResultMapper.isSensitiveColumn(null));
    }

    @Test
    @DisplayName("every name on the exact masked list is sensitive")
    void exactMaskedNamesAreSensitive() {
        for (String name : List.of(
                "password_hash",
                "totp_secret",
                "secret_value",
                "sql",
                "api_key",
                "secret",
                "private_key",
                "access_token",
                "refresh_token",
                "auth_token")) {
            assertTrue(DbResultMapper.isSensitiveColumn(name), name + " must be masked");
        }
    }

    @Test
    @DisplayName("matching is case-insensitive")
    void matchingIsCaseInsensitive() {
        assertTrue(DbResultMapper.isSensitiveColumn("PASSWORD_HASH"));
        assertTrue(DbResultMapper.isSensitiveColumn("Api_Key"));
        assertTrue(DbResultMapper.isSensitiveColumn("ACCESS_TOKEN"));
    }

    @Test
    @DisplayName("substring rules catch names that are not on the exact list")
    void substringRulesCatchOtherNames() {
        assertTrue(DbResultMapper.isSensitiveColumn("user_password"), "contains 'password'");
        assertTrue(DbResultMapper.isSensitiveColumn("totp_backup"), "contains 'totp'");
        assertTrue(DbResultMapper.isSensitiveColumn("my_secret_thing"), "contains 'secret'");
        assertTrue(DbResultMapper.isSensitiveColumn("tenant_private_key_id"), "contains 'private_key'");
        assertTrue(DbResultMapper.isSensitiveColumn("csrf_token"), "contains 'token'");
    }

    @Test
    @DisplayName("token counters and usage meters are exempt from the token rule")
    void tokenCountAndUsageAreExempt() {
        // These are aggregate counters, not credentials — masking them would break the console.
        assertFalse(DbResultMapper.isSensitiveColumn("token_count"));
        assertFalse(DbResultMapper.isSensitiveColumn("token_usage"));
        assertFalse(DbResultMapper.isSensitiveColumn("TOKEN_COUNT"));
    }

    @Test
    @DisplayName("an ordinary column is not sensitive")
    void ordinaryColumnIsNotSensitive() {
        assertFalse(DbResultMapper.isSensitiveColumn("id"));
        assertFalse(DbResultMapper.isSensitiveColumn("created_at"));
        assertFalse(DbResultMapper.isSensitiveColumn("name"));
    }

    @Test
    @DisplayName("sensitive columns are redacted per row and ordinary ones are passed through")
    void sensitiveColumnsAreRedactedPerRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(meta.getColumnLabel(2)).thenReturn("password_hash");
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn(1, 2);

        DbQueryResult result = DbResultMapper.mapResultSet(rs, 10);

        assertEquals(List.of("id", "password_hash"), result.columns());
        assertEquals(2, result.rows().size());
        assertEquals(List.of(1, "[REDACTED]"), result.rows().get(0));
        assertEquals(List.of(2, "[REDACTED]"), result.rows().get(1));
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("the sensitive column's value is never read from the result set")
    void sensitiveColumnValueIsNeverRead() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("api_key");
        when(rs.next()).thenReturn(true, false);

        DbQueryResult result = DbResultMapper.mapResultSet(rs, 10);

        assertEquals(List.of(List.of("[REDACTED]")), result.rows());
        Mockito.verify(rs, Mockito.never()).getObject(1);
    }

    @Test
    @DisplayName("a result set with no rows yields no rows and is not truncated")
    void emptyResultSetIsNotTruncated() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        DbQueryResult result = DbResultMapper.mapResultSet(rs, 10);

        assertTrue(result.rows().isEmpty());
        assertFalse(result.truncated());
        assertEquals(List.of("id"), result.columns());
    }

    @Test
    @DisplayName("rows beyond the limit are dropped and the result is marked truncated")
    void rowsBeyondTheLimitAreTruncated() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn(1);

        DbQueryResult result = DbResultMapper.mapResultSet(rs, 1);

        assertEquals(1, result.rows().size());
        assertEquals(List.of(1), result.rows().get(0));
        assertTrue(result.truncated(), "a second row existed, so the result must be flagged as truncated");
    }

    @Test
    @DisplayName("exactly at the limit is not truncated")
    void exactlyAtTheLimitIsNotTruncated() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(7);

        DbQueryResult result = DbResultMapper.mapResultSet(rs, 1);

        assertEquals(List.of(List.of(7)), result.rows());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("a null query hashes to the literal 'null'")
    void nullQueryHashesToNullLiteral() {
        assertEquals("null", DbResultMapper.hashQuery(null));
    }

    @Test
    @DisplayName("hashing produces the standard SHA-256 hex digest")
    void hashingProducesSha256Hex() {
        // Standard SHA-256 test vector for "abc".
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DbResultMapper.hashQuery("abc"));
    }

    @Test
    @DisplayName("hashing is deterministic and distinguishes different queries")
    void hashingIsDeterministic() {
        String a = DbResultMapper.hashQuery("SELECT 1");
        String b = DbResultMapper.hashQuery("SELECT 1");
        String c = DbResultMapper.hashQuery("SELECT 2");

        assertEquals(a, b);
        assertEquals(64, a.length(), "SHA-256 hex digest is 64 characters");
        assertNotEquals(a, c);
    }
}
