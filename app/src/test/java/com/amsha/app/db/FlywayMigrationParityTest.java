package com.amsha.app.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * Asserts that the SQLite and PostgreSQL Flyway migration sets describe the same schema.
 *
 * <p>{@code AGENTS.md} requires backends and migration parity to move in one change, but until now nothing enforced
 * it — the two sets could drift silently, and the Phase 3 contract suites found several live examples of
 * backend-visible behaviour differing in ways a migration review would not have caught.
 *
 * <p><b>What is compared.</b> For each of the 12 {@code V<n>__<description>.sql} pairs: the static table set, every
 * shared table's column names and {@code NOT NULL} flags, the statically created index names, and the columns added
 * by {@code ALTER TABLE ... ADD COLUMN}. Column <em>types</em> are deliberately not compared — {@code TEXT} vs
 * {@code TIMESTAMP WITH TIME ZONE} and {@code INTEGER} vs {@code BIGINT} are the expected dialect mappings, not
 * drift.
 *
 * <p><b>Why some things are excluded.</b> The two dialects legitimately differ in three places, each pinned by name
 * below rather than ignored by a loose pattern:
 * <ul>
 *   <li>PostgreSQL wraps pgvector DDL in {@code DO $$ ... $$;} blocks so it can be skipped when the extension is
 *       unavailable; SQLite has no equivalent, so the whole block is removed before comparison.</li>
 *   <li>SQLite creates {@code fts5} full-text indexes as {@code CREATE VIRTUAL TABLE}; PostgreSQL uses
 *       {@code tsvector} columns with {@code GIN} indexes instead.</li>
 *   <li>Those {@code tsvector} columns are added by {@code ALTER TABLE}, so they show up as PostgreSQL-only column
 *       additions.</li>
 * </ul>
 *
 * <p>The exception sets are asserted for <b>exact equality</b>, not as a superset: adding a new dialect-only index
 * fails this test, and so does removing a construct that is still listed here. That is what stops the allow-list
 * from rotting into a blanket exemption.
 *
 * <p>The migrations are read from the working tree rather than the classpath, so the comparison is against the
 * sources under review and not whatever was last installed into {@code ~/.m2}.
 */
class FlywayMigrationParityTest {

    private static final String SQLITE_MODULE = "persistence-sqlite";
    private static final String POSTGRES_MODULE = "persistence-postgres";
    private static final String SQLITE_MIGRATIONS = "src/main/resources/db/migration/sqlite";
    private static final String POSTGRES_MIGRATIONS = "src/main/resources/db/migration/postgresql";

    /** PostgreSQL-only GIN indexes backing the {@code content_tsv} columns; SQLite uses fts5 virtual tables. */
    private static final Set<String> POSTGRES_ONLY_INDEXES =
            Set.of("idx_archival_content_fts", "idx_recall_content_fts");

    /** PostgreSQL-only {@code tsvector} columns added by {@code ALTER TABLE}; the fts5 counterpart of each. */
    private static final Set<String> POSTGRES_ONLY_ADDED_COLUMNS =
            Set.of("recall_messages.content_tsv", "archival_chunks.content_tsv");

    /** SQLite's fts5 virtual tables — the construct that replaces the PostgreSQL {@code tsvector} columns. */
    private static final Set<String> SQLITE_ONLY_VIRTUAL_TABLES = Set.of("recall_messages_fts", "archival_chunks_fts");

    /** Tables PostgreSQL creates only inside a pgvector-conditional {@code DO} block. */
    private static final Set<String> POSTGRES_DYNAMIC_TABLES = Set.of("rag_chunk_embedding");

    private static final Pattern DO_BLOCK =
            Pattern.compile("DO \\$\\$.*?\\$\\$;", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern VIRTUAL_TABLE_STATEMENT =
            Pattern.compile("CREATE VIRTUAL TABLE (?:IF NOT EXISTS )?([A-Za-z_]\\w*)[^;]*;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?([A-Za-z_]\\w*)\\s*\\((.*?)\\)\\s*;", Pattern.DOTALL);
    private static final Pattern CREATE_TABLE_KEYWORD = Pattern.compile("CREATE TABLE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_INDEX =
            Pattern.compile("CREATE (?:UNIQUE )?INDEX (?:IF NOT EXISTS )?([A-Za-z_]\\w*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_ADD_COLUMN = Pattern.compile(
            "ALTER TABLE ([A-Za-z_]\\w*) ADD COLUMN (?:IF NOT EXISTS )?([A-Za-z_]\\w*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_CONSTRAINT =
            Pattern.compile("(PRIMARY|FOREIGN|UNIQUE|CHECK|CONSTRAINT)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_NAME = Pattern.compile("^([A-Za-z_]\\w*)");
    private static final Pattern NOT_NULL = Pattern.compile("\\bNOT NULL\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIGRATION_NAME = Pattern.compile("V(\\d+)__(.+)\\.sql");

    // ─── migration file set ────────────────────────────────────

    @Test
    void bothDialectsShipTheSameMigrationFilesInTheSameOrder() {
        List<Migration> sqlite = migrations(SQLITE_MODULE, SQLITE_MIGRATIONS);
        List<Migration> postgres = migrations(POSTGRES_MODULE, POSTGRES_MIGRATIONS);

        assertFalse(sqlite.isEmpty(), "no migrations were found — the path resolution is broken, not the schema");
        assertEquals(
                sqlite.stream().map(Migration::name).toList(),
                postgres.stream().map(Migration::name).toList(),
                "the two dialect directories must hold identically named migrations");

        assertEquals(
                IntStream.rangeClosed(1, sqlite.size()).boxed().toList(),
                sqlite.stream().map(Migration::version).toList(),
                "versions must be 1..N with no gaps or duplicates");
    }

    // ─── tables ────────────────────────────────────────────────

    @Test
    void staticTableSetsAreIdentical() {
        Map<String, String> sqlite = staticSource(SQLITE_MODULE, SQLITE_MIGRATIONS);
        Map<String, String> postgres = staticSource(POSTGRES_MODULE, POSTGRES_MIGRATIONS);

        assertParserFoundEveryCreateTable(sqlite);
        assertParserFoundEveryCreateTable(postgres);

        assertEquals(
                createTables(sqlite).keySet(),
                createTables(postgres).keySet(),
                "the set of statically created tables must match across dialects");
    }

    @Test
    void everySharedTableHasTheSameColumnsAndNullability() {
        Map<String, Map<String, Boolean>> sqlite = createTables(staticSource(SQLITE_MODULE, SQLITE_MIGRATIONS));
        Map<String, Map<String, Boolean>> postgres = createTables(staticSource(POSTGRES_MODULE, POSTGRES_MIGRATIONS));

        assertFalse(sqlite.isEmpty());

        sqlite.forEach((table, columns) -> {
            Map<String, Boolean> counterpart = postgres.get(table);
            if (counterpart == null) {
                return; // reported by staticTableSetsAreIdentical
            }
            assertEquals(columns.keySet(), counterpart.keySet(), table + ": column names differ");
            columns.forEach((column, notNull) ->
                    assertEquals(notNull, counterpart.get(column), table + "." + column + ": NOT NULL differs"));
        });
    }

    // ─── indexes ───────────────────────────────────────────────

    @Test
    void indexSetsMatchApartFromTheDocumentedDialectOnlyIndexes() {
        Set<String> sqlite = indexNames(staticSource(SQLITE_MODULE, SQLITE_MIGRATIONS));
        Set<String> postgres = indexNames(staticSource(POSTGRES_MODULE, POSTGRES_MIGRATIONS));

        assertFalse(sqlite.isEmpty());
        assertTrue(sqlite.containsAll(Set.of("idx_archival_user", "idx_archival_type")), "index parsing looks broken");

        assertEquals(Set.of(), difference(sqlite, postgres), "PostgreSQL is missing indexes SQLite creates");
        assertEquals(
                POSTGRES_ONLY_INDEXES,
                difference(postgres, sqlite),
                "PostgreSQL-only indexes changed — add the new one here only if it is genuinely dialect-specific");
    }

    // ─── altered columns ───────────────────────────────────────

    @Test
    void alterTableColumnAdditionsMatchApartFromTheDocumentedDialectOnlyColumns() {
        Set<String> sqlite = addedColumns(staticSource(SQLITE_MODULE, SQLITE_MIGRATIONS));
        Set<String> postgres = addedColumns(staticSource(POSTGRES_MODULE, POSTGRES_MIGRATIONS));

        assertTrue(sqlite.contains("mcp_connection.revision"), "ALTER parsing looks broken");

        assertEquals(Set.of(), difference(sqlite, postgres), "PostgreSQL is missing columns SQLite adds");
        assertEquals(
                POSTGRES_ONLY_ADDED_COLUMNS,
                difference(postgres, sqlite),
                "PostgreSQL-only added columns changed — add the new one here only if it is genuinely dialect-specific");
    }

    // ─── anti-rot guards for the exceptions above ──────────────

    /**
     * Every entry in the dialect-only allow-lists must still be present in the sources it excuses. Without this, a
     * rename or deletion upstream would leave a stale exemption that silently widens the blind spot.
     */
    @Test
    void everyDocumentedDialectExceptionIsStillPresent() {
        Set<String> postgresIndexes = indexNames(staticSource(POSTGRES_MODULE, POSTGRES_MIGRATIONS));
        Set<String> postgresColumns = addedColumns(staticSource(POSTGRES_MODULE, POSTGRES_MIGRATIONS));
        Set<String> sqliteVirtual = virtualTables(rawSource(SQLITE_MODULE, SQLITE_MIGRATIONS));
        Set<String> postgresDynamic = dynamicTables(rawSource(POSTGRES_MODULE, POSTGRES_MIGRATIONS));

        assertEquals(Set.of(), difference(POSTGRES_ONLY_INDEXES, postgresIndexes), "stale index exemption");
        assertEquals(Set.of(), difference(POSTGRES_ONLY_ADDED_COLUMNS, postgresColumns), "stale column exemption");
        assertEquals(Set.of(), difference(SQLITE_ONLY_VIRTUAL_TABLES, sqliteVirtual), "stale virtual-table exemption");
        assertEquals(Set.of(), difference(POSTGRES_DYNAMIC_TABLES, postgresDynamic), "stale dynamic-table exemption");
    }

    /**
     * The constructs excluded from the comparison are themselves pinned, so a change in <em>how</em> a dialect
     * implements full-text search or vector storage is visible here rather than absorbed by the exclusion.
     */
    @Test
    void theDialectSpecificConstructsAreExactlyWhatIsDocumented() {
        assertEquals(
                SQLITE_ONLY_VIRTUAL_TABLES,
                virtualTables(rawSource(SQLITE_MODULE, SQLITE_MIGRATIONS)),
                "SQLite's fts5 virtual tables changed");
        assertEquals(
                POSTGRES_DYNAMIC_TABLES,
                dynamicTables(rawSource(POSTGRES_MODULE, POSTGRES_MIGRATIONS)),
                "PostgreSQL's pgvector-conditional tables changed");
    }

    // ─── source access ─────────────────────────────────────────

    private record Migration(int version, String name, Path path) {}

    private static List<Migration> migrations(String module, String relativeDir) {
        Path dir = resolve(module, relativeDir);
        List<Migration> found = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(file -> {
                Matcher m = MIGRATION_NAME.matcher(file.getFileName().toString());
                if (m.matches()) {
                    found.add(new Migration(Integer.parseInt(m.group(1)), m.group(1) + "__" + m.group(2), file));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
        found.sort((a, b) -> Integer.compare(a.version(), b.version()));
        return found;
    }

    /** Every migration of a dialect, concatenated, with the dialect-specific constructs removed. */
    private static Map<String, String> staticSource(String module, String relativeDir) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Migration m : migrations(module, relativeDir)) {
            out.put(m.name(), stripDialectSpecific(read(m.path())));
        }
        return out;
    }

    private static Map<String, String> rawSource(String module, String relativeDir) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Migration m : migrations(module, relativeDir)) {
            out.put(m.name(), read(m.path()));
        }
        return out;
    }

    /**
     * Locates a sibling module's directory by walking up from the working directory. Surefire runs with the module
     * basedir as the working directory (the same assumption {@code ApiKeyAuthFilterTest} makes), but the walk also
     * tolerates being launched from the reactor root. Fails loudly rather than skipping, so this can never become a
     * vacuous green.
     */
    private static Path resolve(String module, String relativeDir) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(module).resolve(relativeDir);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate " + module + "/" + relativeDir + " from "
                + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    // ─── DDL parsing ───────────────────────────────────────────

    private static String stripDialectSpecific(String sql) {
        return VIRTUAL_TABLE_STATEMENT
                .matcher(DO_BLOCK.matcher(sql).replaceAll(" "))
                .replaceAll(" ");
    }

    private static Set<String> virtualTables(Map<String, String> sources) {
        Set<String> names = new TreeSet<>();
        sources.values().forEach(sql -> {
            Matcher m = VIRTUAL_TABLE_STATEMENT.matcher(sql);
            while (m.find()) {
                names.add(m.group(1).toLowerCase());
            }
        });
        return names;
    }

    private static Set<String> dynamicTables(Map<String, String> sources) {
        Set<String> names = new TreeSet<>();
        sources.values().forEach(sql -> {
            Matcher block = DO_BLOCK.matcher(sql);
            while (block.find()) {
                Matcher table = Pattern.compile(
                                "CREATE TABLE (?:IF NOT EXISTS )?([A-Za-z_]\\w*)", Pattern.CASE_INSENSITIVE)
                        .matcher(block.group());
                while (table.find()) {
                    names.add(table.group(1).toLowerCase());
                }
            }
        });
        return names;
    }

    private static Map<String, Map<String, Boolean>> createTables(Map<String, String> sources) {
        Map<String, Map<String, Boolean>> tables = new TreeMap<>();
        sources.values().forEach(sql -> {
            Matcher m = CREATE_TABLE.matcher(sql);
            while (m.find()) {
                tables.put(m.group(1).toLowerCase(), columnsOf(m.group(2)));
            }
        });
        return tables;
    }

    /**
     * Guards against the parser silently matching nothing: if a future migration uses a syntax the pattern does not
     * understand, the parsed table count falls behind the number of {@code CREATE TABLE} keywords and this fails
     * instead of quietly comparing empty maps.
     */
    private static void assertParserFoundEveryCreateTable(Map<String, String> sources) {
        long keywords = sources.values().stream()
                .mapToLong(sql -> CREATE_TABLE_KEYWORD.matcher(sql).results().count())
                .sum();
        long parsed = sources.values().stream()
                .mapToLong(sql -> CREATE_TABLE.matcher(sql).results().count())
                .sum();
        assertEquals(keywords, parsed, "the CREATE TABLE parser did not understand every statement");
        assertTrue(keywords > 0, "no CREATE TABLE statements were found at all");
    }

    private static Map<String, Boolean> columnsOf(String body) {
        Map<String, Boolean> columns = new LinkedHashMap<>();
        for (String part : splitTopLevel(body)) {
            String line = part.strip();
            if (line.isEmpty() || TABLE_CONSTRAINT.matcher(line).lookingAt()) {
                continue;
            }
            Matcher name = COLUMN_NAME.matcher(line);
            if (name.find()) {
                columns.put(name.group(1).toLowerCase(), NOT_NULL.matcher(line).find());
            }
        }
        return columns;
    }

    /** Splits a table body on commas that are not inside parentheses, so {@code NUMERIC(10,6)} stays intact. */
    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static Set<String> indexNames(Map<String, String> sources) {
        Set<String> names = new TreeSet<>();
        sources.values().forEach(sql -> {
            Matcher m = CREATE_INDEX.matcher(sql);
            while (m.find()) {
                names.add(m.group(1).toLowerCase());
            }
        });
        return names;
    }

    /** {@code table.column} for every column added by {@code ALTER TABLE ... ADD COLUMN}. */
    private static Set<String> addedColumns(Map<String, String> sources) {
        Set<String> columns = new TreeSet<>();
        sources.values().forEach(sql -> {
            Matcher m = ALTER_ADD_COLUMN.matcher(sql);
            while (m.find()) {
                columns.add(m.group(1).toLowerCase() + "." + m.group(2).toLowerCase());
            }
        });
        return columns;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> out = new TreeSet<>(left);
        out.removeAll(right);
        return out;
    }
}
