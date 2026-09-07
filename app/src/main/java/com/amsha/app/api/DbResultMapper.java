package com.amsha.app.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Helper for mapping and redacting JDBC database console query results. */
final class DbResultMapper {

    private static final Set<String> MASKED_COLUMNS = Set.of(
            "password_hash",
            "totp_secret",
            "secret_value",
            "sql",
            "api_key",
            "secret",
            "private_key",
            "access_token",
            "refresh_token",
            "auth_token");

    private DbResultMapper() {}

    static boolean isSensitiveColumn(String colName) {
        if (colName == null) return false;
        String lower = colName.toLowerCase(Locale.ROOT);
        return MASKED_COLUMNS.contains(lower)
                || lower.contains("password")
                || lower.contains("totp")
                || lower.contains("secret")
                || lower.contains("private_key")
                || (lower.contains("token") && !lower.contains("count") && !lower.contains("usage"));
    }

    static DbQueryResult mapResultSet(ResultSet rs, int limit) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(colCount);
        boolean[] isSensitive = new boolean[colCount];

        for (int i = 1; i <= colCount; i++) {
            String colName = meta.getColumnLabel(i);
            columns.add(colName);
            if (isSensitiveColumn(colName)) {
                isSensitive[i - 1] = true;
            }
        }

        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() < limit) {
                List<Object> row = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    if (isSensitive[i - 1]) {
                        row.add("[REDACTED]");
                    } else {
                        row.add(rs.getObject(i));
                    }
                }
                rows.add(row);
            } else {
                truncated = true;
                break;
            }
        }

        return new DbQueryResult(columns, rows, truncated);
    }

    static String hashQuery(String sql) {
        if (sql == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException _) {
            return "sha256-error";
        }
    }

    record DbQueryResult(List<String> columns, List<List<Object>> rows, boolean truncated) {}
}
