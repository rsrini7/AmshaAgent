package com.amsha.app.db;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates and transforms database queries for the read-only DB console using JSqlParser AST analysis.
 *
 * <p>Enforces AST-level single-statement execution, exact PRAGMA allowlist, recursive DML/DDL rejection,
 * dangerous function blacklisting, schema column filtering, and two-tier parameterized view isolation.
 */
public final class DbQueryValidator {

    public static final int MAX_QUERY_LENGTH = 1000;

    public static final String VIEW_SESSION_HISTORY = "session_history_view";
    public static final String VIEW_RECENT_TRACES = "recent_traces_view";
    public static final String VIEW_MEMORY_CHUNKS = "memory_chunks_view";
    public static final String VIEW_SESSION_CATALOG = "session_catalog_view";

    public static final Set<String> ALLOWED_VIEWS =
            Set.of(VIEW_SESSION_HISTORY, VIEW_RECENT_TRACES, VIEW_MEMORY_CHUNKS, VIEW_SESSION_CATALOG);

    public static final Set<String> SYSTEM_METADATA_TABLES =
            Set.of("sqlite_master", "sqlite_schema", "information_schema.tables", "flyway_schema_history");

    public static final Set<String> BLOCKED_TABLES =
            Set.of("encrypted_secrets", "api_credential", "llm_connection_secret_ref");

    public static final Set<String> DATA_TABLES = Set.of(
            "recall_messages",
            "chat_memory",
            "archival_chunks",
            "session_catalog",
            "agent_checkpoint",
            "execution_trace",
            "identity_user",
            "external_identity",
            "audit_event");

    private static final Pattern DANGEROUS_PATTERNS =
            Pattern.compile("(?i)\\b(load_extension|writefile|readfile|attach|detach)\\b");

    private static final List<Pattern> ALLOWED_PRAGMAS = List.of(
            Pattern.compile("(?i)^PRAGMA\\s+table_info\\s*\\(\\s*['\"]?[a-zA-Z0-9_]+['\"]?\\s*\\)$"),
            Pattern.compile("(?i)^PRAGMA\\s+table_list$"),
            Pattern.compile("(?i)^PRAGMA\\s+index_list\\s*\\(\\s*['\"]?[a-zA-Z0-9_]+['\"]?\\s*\\)$"),
            Pattern.compile("(?i)^PRAGMA\\s+foreign_key_list\\s*\\(\\s*['\"]?[a-zA-Z0-9_]+['\"]?\\s*\\)$"));

    private static final Pattern TARGETED_PRAGMA = Pattern.compile(
            "(?i)^PRAGMA\\s+(?:table_info|index_list|foreign_key_list)\\s*\\(\\s*['\"]?([a-zA-Z0-9_]+)['\"]?\\s*\\)$");

    public record ValidatedQuery(String sql, List<Object> parameters, boolean isPragma, String targetView) {
        public ValidatedQuery(String sql, List<Object> parameters, boolean isPragma) {
            this(sql, parameters, isPragma, null);
        }

        public boolean requiresSessionVerification() {
            return VIEW_SESSION_HISTORY.equalsIgnoreCase(targetView) || VIEW_RECENT_TRACES.equalsIgnoreCase(targetView);
        }
    }

    private DbQueryValidator() {}

    /**
     * Validates a raw SQL query using AST inspection and applies tenant view rewriting.
     */
    public static ValidatedQuery validateAndScope(String rawSql, String workspaceId, String sessionId) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new DbValidationException("Query cannot be empty");
        }
        if (rawSql.length() > MAX_QUERY_LENGTH) {
            throw new DbValidationException(
                    "Query length exceeds maximum limit of " + MAX_QUERY_LENGTH + " characters");
        }

        String stripped = stripCommentsAndValidateSemicolon(rawSql);

        if (stripped.toLowerCase(Locale.ROOT).startsWith("pragma")) {
            validatePragma(stripped);
            return new ValidatedQuery(stripped, List.of(), true);
        }

        Statement statement = parseSingleStatement(stripped);
        Select selectStatement = validateRootStatement(statement);
        validateCtes(selectStatement);
        checkDangerousFunctions(stripped);

        // The console binds parameters itself (tenant view scoping); a caller-supplied '?' would
        // shift those bindings and silently query the wrong tenant, so reject it up front.
        if (!DbQueryAstScanner.scan(selectStatement).jdbcParameters().isEmpty()) {
            throw new DbValidationException(
                    "Literal '?' placeholders are not supported; the console binds query parameters itself");
        }

        List<String> tables = new TablesNamesFinder().getTableList(statement);
        validateTablesAndSchemaColumns(tables, selectStatement);

        return transformParameterizedViews(selectStatement, workspaceId, sessionId);
    }

    private static Statement parseSingleStatement(String sql) {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (Exception e) {
            throw new DbValidationException("SQL syntax error");
        }
        if (statements == null || statements.getStatements().size() != 1) {
            throw new DbValidationException("Multi-statement queries are forbidden");
        }
        return statements.getStatements().get(0);
    }

    private static Select validateRootStatement(Statement statement) {
        Select innerSelect;
        if (statement instanceof Select select) {
            innerSelect = select;
        } else if (statement instanceof ExplainStatement explain && explain.getStatement() != null) {
            innerSelect = explain.getStatement();
        } else {
            throw new DbValidationException("Only read-only SELECT or EXPLAIN statements are permitted (received: "
                    + statement.getClass().getSimpleName() + ")");
        }

        if (innerSelect instanceof SetOperationList) {
            throw new DbValidationException("Set operations (UNION, INTERSECT, EXCEPT) are forbidden");
        }
        PlainSelect ps = extractPlainSelect(innerSelect);
        if (ps != null && ps.getIntoTables() != null && !ps.getIntoTables().isEmpty()) {
            throw new DbValidationException("SELECT INTO statements are forbidden");
        }
        return innerSelect;
    }

    private static PlainSelect extractPlainSelect(Select select) {
        if (select instanceof PlainSelect ps) {
            return ps;
        }
        if (select instanceof ParenthesedSelect pSel && pSel.getSelect() != null) {
            return extractPlainSelect(pSel.getSelect());
        }
        return null;
    }

    private static void validateCtes(Select select) {
        if (select.getWithItemsList() != null) {
            for (WithItem item : select.getWithItemsList()) {
                if (item.getSelect() == null) {
                    throw new DbValidationException("CTE must define a valid read-only SELECT");
                }
            }
        }
    }

    /**
     * Checks for prohibited extension loading or file I/O commands using regex.
     * Note: This inspects the stripped query including string literals, intentionally
     * failing closed if a prohibited keyword appears inside quotes (e.g. SELECT 'load_extension').
     */
    private static void checkDangerousFunctions(String sql) {
        var matcher = DANGEROUS_PATTERNS.matcher(sql);
        if (matcher.find()) {
            throw new DbValidationException("Call to prohibited function or command: " + matcher.group(1));
        }
    }

    private static void validateTablesAndSchemaColumns(List<String> tables, Select select) {
        boolean referencesAllowedView = false;
        boolean referencesSchemaMetadata = false;
        for (String rawTable : tables) {
            String table = cleanIdentifier(rawTable);
            if (BLOCKED_TABLES.contains(table)) {
                throw new DbValidationException("Access to sensitive table '" + table + "' is forbidden");
            }
            if (DATA_TABLES.contains(table)) {
                throw new DbValidationException("Direct access to data table '" + table
                        + "' is prohibited. Use allowlisted parameterized views (" + String.join(", ", ALLOWED_VIEWS)
                        + ") or schema introspection.");
            }
            if (ALLOWED_VIEWS.contains(table)) {
                referencesAllowedView = true;
            } else if (!SYSTEM_METADATA_TABLES.contains(table)) {
                throw new DbValidationException("Table '" + table
                        + "' is not permitted. Only allowlisted views or system metadata tables may be queried.");
            }
            if (isSchemaMetadataTable(table)) {
                referencesSchemaMetadata = true;
            }
        }

        if (referencesSchemaMetadata) {
            PlainSelect ps = extractPlainSelect(select);
            Table rootTable = (ps != null && ps.getFromItem() instanceof Table t) ? t : null;
            boolean isDirectRootFrom = rootTable != null
                    && isSchemaMetadataTable(rootTable)
                    && (ps.getJoins() == null || ps.getJoins().isEmpty())
                    && (select.getWithItemsList() == null
                            || select.getWithItemsList().isEmpty());
            if (!isDirectRootFrom) {
                throw new DbValidationException(
                        "Schema metadata tables (" + String.join(", ", SYSTEM_METADATA_TABLES)
                                + ") may only be queried directly as the root FROM table (joins, CTEs, and scalar subqueries are prohibited)");
            }
            String rootName =
                    rootTable.getFullyQualifiedName() != null ? rootTable.getFullyQualifiedName() : rootTable.getName();
            checkSchemaColumns(select, rootName);
        }

        if (referencesAllowedView) {
            PlainSelect ps = extractPlainSelect(select);
            boolean isDirectRootFrom = ps != null
                    && ps.getFromItem() instanceof Table rootTable
                    && ALLOWED_VIEWS.contains(cleanIdentifier(rootTable.getName()))
                    && (ps.getJoins() == null || ps.getJoins().isEmpty())
                    && (select.getWithItemsList() == null
                            || select.getWithItemsList().isEmpty());
            long viewCount = tables.stream()
                    .filter(t -> ALLOWED_VIEWS.contains(cleanIdentifier(t)))
                    .count();
            if (!isDirectRootFrom || viewCount != 1) {
                throw new DbValidationException(
                        "Allowlisted parameterized views (" + String.join(", ", ALLOWED_VIEWS)
                                + ") may only be queried directly as the root FROM table (nested subqueries, CTEs, and JOINs are prohibited)");
            }
        }
    }

    public static final Set<String> APPROVED_SCHEMA_METADATA_COLUMNS = Set.of("type", "name", "tbl_name", "rootpage");

    private static final Map<String, Set<String>> APPROVED_METADATA_COLUMNS_BY_TABLE = Map.of(
            "sqlite_master", APPROVED_SCHEMA_METADATA_COLUMNS,
            "sqlite_schema", APPROVED_SCHEMA_METADATA_COLUMNS,
            "information_schema.tables", Set.of("table_catalog", "table_schema", "table_name", "table_type"),
            "tables", Set.of("table_catalog", "table_schema", "table_name", "table_type"),
            "flyway_schema_history",
                    Set.of(
                            "installed_rank",
                            "version",
                            "description",
                            "type",
                            "script",
                            "checksum",
                            "installed_by",
                            "installed_on",
                            "execution_time",
                            "success"));

    private static boolean isSchemaMetadataTable(Table table) {
        return table != null
                && (isSchemaMetadataTable(table.getFullyQualifiedName()) || isSchemaMetadataTable(table.getName()));
    }

    private static boolean isSchemaMetadataTable(String table) {
        String clean = cleanIdentifier(table);
        return SYSTEM_METADATA_TABLES.contains(clean)
                || "sqlite_master".equalsIgnoreCase(clean)
                || "sqlite_schema".equalsIgnoreCase(clean);
    }

    /**
     * Rejects any non-approved column reference on schema metadata tables.
     *
     * <p>The scan is recursive: a nested sub-select is inspected too, so
     * {@code SELECT (SELECT sql FROM sqlite_master LIMIT 1)} cannot smuggle the full DDL past
     * the allowlist by hiding the column inside a subquery.
     */
    private static void checkSchemaColumns(Select select, String rootTableName) {
        PlainSelect ps = extractPlainSelect(select);
        if (ps == null || ps.getSelectItems() == null) return;

        DbQueryAstScanner scanner = DbQueryAstScanner.scan(select);
        Set<String> approved = APPROVED_METADATA_COLUMNS_BY_TABLE.getOrDefault(
                cleanIdentifier(rootTableName), APPROVED_SCHEMA_METADATA_COLUMNS);

        if (!scanner.wildcards().isEmpty()) {
            throw new DbValidationException(
                    "Wildcard '*' is forbidden on schema metadata tables. Specify approved columns: "
                            + String.join(", ", approved));
        }

        for (Column col : scanner.columns()) {
            String colName = cleanColumnName(col.getColumnName());
            if ("sql".equals(colName) || !approved.contains(colName)) {
                throw new DbValidationException("Access to column '" + colName
                        + "' in schema metadata is forbidden. Allowed columns: "
                        + String.join(", ", approved));
            }
        }
    }

    private static String cleanColumnName(String raw) {
        if (raw == null) return "";
        return raw.replace("\"", "")
                .replace("`", "")
                .replace("[", "")
                .replace("]", "")
                .replace("'", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static ValidatedQuery transformParameterizedViews(Select select, String workspaceId, String sessionId) {
        PlainSelect ps = extractPlainSelect(select);
        if (ps == null || !(ps.getFromItem() instanceof Table table)) {
            return new ValidatedQuery(select.toString(), List.of(), false);
        }

        String viewName = cleanIdentifier(table.getName());
        if (!ALLOWED_VIEWS.contains(viewName)) {
            return new ValidatedQuery(select.toString(), List.of(), false);
        }

        if (workspaceId == null || workspaceId.isBlank()) {
            throw new DbValidationException("Active workspace ID is mandatory for parameterized view queries");
        }

        boolean requiresSession =
                VIEW_SESSION_HISTORY.equalsIgnoreCase(viewName) || VIEW_RECENT_TRACES.equalsIgnoreCase(viewName);
        if (requiresSession && (sessionId == null || sessionId.isBlank())) {
            throw new DbValidationException("Active session ID is mandatory for view " + viewName);
        }

        ParenthesedSelect sub = new ParenthesedSelect();
        List<Object> params = new ArrayList<>();

        switch (viewName) {
            case VIEW_SESSION_HISTORY -> {
                sub.setSelect(parseSubquery("SELECT rm.* FROM recall_messages rm "
                        + "INNER JOIN session_catalog sc ON rm.session_id = sc.session_id "
                        + "WHERE sc.owner_key IN (SELECT wm.user_id FROM workspace_membership wm WHERE wm.workspace_id = ?) "
                        + "AND rm.session_id = ?"));
                params.add(workspaceId);
                params.add(sessionId);
            }
            case VIEW_RECENT_TRACES -> {
                sub.setSelect(parseSubquery("SELECT cm.* FROM chat_memory cm "
                        + "INNER JOIN session_catalog sc ON cm.memory_id = sc.session_id "
                        + "WHERE sc.owner_key IN (SELECT wm.user_id FROM workspace_membership wm WHERE wm.workspace_id = ?) "
                        + "AND sc.session_id = ?"));
                params.add(workspaceId);
                params.add(sessionId);
            }
            case VIEW_MEMORY_CHUNKS -> {
                sub.setSelect(
                        parseSubquery(
                                "SELECT ac.* FROM archival_chunks ac "
                                        + "WHERE ac.user_id IN (SELECT wm.user_id FROM workspace_membership wm WHERE wm.workspace_id = ?)"));
                params.add(workspaceId);
            }
            case VIEW_SESSION_CATALOG -> {
                sub.setSelect(
                        parseSubquery(
                                "SELECT sc.* FROM session_catalog sc "
                                        + "WHERE sc.owner_key IN (SELECT wm.user_id FROM workspace_membership wm WHERE wm.workspace_id = ?)"));
                params.add(workspaceId);
            }
            default -> throw new DbValidationException("Unsupported view: " + viewName);
        }

        // Preserve a caller-supplied alias (e.g. "FROM session_history_view sh") so qualified
        // column references in WHERE/ORDER BY still resolve after the rewrite.
        Alias alias = table.getAlias() != null ? table.getAlias() : new Alias(viewName);
        alias.setUseAs(true);
        sub.setAlias(alias);
        ps.setFromItem(sub);

        return new ValidatedQuery(select.toString(), params, false, viewName);
    }

    private static Select parseSubquery(String sql) {
        try {
            return (Select) CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new DbValidationException("Failed to construct view subquery: " + e.getMessage());
        }
    }

    private static void validatePragma(String sql) {
        for (Pattern pattern : ALLOWED_PRAGMAS) {
            if (pattern.matcher(sql.trim()).matches()) {
                var target = TARGETED_PRAGMA.matcher(sql.trim());
                if (target.matches() && BLOCKED_TABLES.contains(target.group(1).toLowerCase(Locale.ROOT))) {
                    throw new DbValidationException("Sensitive schema introspection target is forbidden");
                }
                return;
            }
        }
        throw new DbValidationException(
                "Unauthorized PRAGMA: only table_info, table_list, index_list, and foreign_key_list are permitted");
    }

    private static String cleanIdentifier(String name) {
        if (name == null) return "";
        return name.replace("\"", "").replace("`", "").replace("'", "").trim().toLowerCase(Locale.ROOT);
    }

    private static String stripCommentsAndValidateSemicolon(String sql) {
        StringBuilder clean = new StringBuilder(sql.length());
        int len = sql.length();
        int i = 0;

        while (i < len) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i = consumeLineComment(sql, i + 2, len);
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = consumeBlockComment(sql, i + 2, len, clean);
                continue;
            }
            if (c == '\'') {
                i = consumeStringLiteral(sql, i, len, clean);
                continue;
            }
            if (c == '"') {
                i = consumeQuotedIdentifier(sql, i, len, clean);
                continue;
            }
            if (c == ';') {
                validateTrailingSemicolon(sql, i + 1, len);
                break;
            }
            clean.append(c);
            i++;
        }
        return clean.toString().trim();
    }

    private static int consumeLineComment(String sql, int start, int len) {
        int i = start;
        while (i < len && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    private static int consumeBlockComment(String sql, int start, int len, StringBuilder clean) {
        int i = start;
        while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
            i++;
        }
        if (i + 1 >= len) {
            throw new DbValidationException("Unclosed block comment");
        }
        clean.append(' ');
        return i + 2;
    }

    private static int consumeStringLiteral(String sql, int start, int len, StringBuilder clean) {
        clean.append('\'');
        int i = start + 1;
        while (i < len) {
            char c = sql.charAt(i);
            clean.append(c);
            if (c == '\'') {
                if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                    clean.append('\'');
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new DbValidationException("Unclosed string literal");
    }

    private static int consumeQuotedIdentifier(String sql, int start, int len, StringBuilder clean) {
        clean.append('"');
        int i = start + 1;
        while (i < len) {
            char c = sql.charAt(i);
            clean.append(c);
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        throw new DbValidationException("Unclosed quoted identifier");
    }

    private static void validateTrailingSemicolon(String sql, int start, int len) {
        for (int j = start; j < len; j++) {
            if (!Character.isWhitespace(sql.charAt(j))) {
                throw new DbValidationException("Multi-statement queries are forbidden");
            }
        }
    }
}
