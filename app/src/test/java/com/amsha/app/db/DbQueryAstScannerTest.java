package com.amsha.app.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.Select;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Direct tests for {@link DbQueryAstScanner}.
 *
 * <p>This scanner is the column-allowlist enforcement point for the DB console: anything it fails to
 * descend into is a column that never reaches the schema-metadata check. The tests therefore drive it
 * through <em>real</em> SQL parsing rather than hand-built ASTs, and each nesting form the class claims
 * to handle (scalar subquery, FROM subquery, JOIN subquery, UNION, parenthesised select) gets its own
 * case.
 */
class DbQueryAstScannerTest {

    private static DbQueryAstScanner scan(String sql) throws Exception {
        return DbQueryAstScanner.scan((Select) CCJSqlParserUtil.parse(sql));
    }

    private static List<String> columnNames(DbQueryAstScanner scanner) {
        return scanner.columns().stream().map(Column::getFullyQualifiedName).toList();
    }

    /** Renders the collected names for an assertion message (assertTrue needs a String, not a List). */
    private static String show(List<String> names) {
        return names.toString();
    }

    @Test
    @DisplayName("a flat select collects its columns and JDBC placeholders")
    void flatSelectCollectsColumnsAndParameters() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT name, type FROM sqlite_master WHERE name = ?");

        assertEquals(List.of("name", "type", "name"), columnNames(scanner));
        assertEquals(1, scanner.jdbcParameters().size());
        assertTrue(scanner.wildcards().isEmpty(), "no star in this query");
    }

    @Test
    @DisplayName("a null select is tolerated and yields an empty scan")
    void nullSelectYieldsEmptyScan() {
        DbQueryAstScanner scanner = DbQueryAstScanner.scan(null);

        assertTrue(scanner.columns().isEmpty());
        assertTrue(scanner.jdbcParameters().isEmpty());
        assertTrue(scanner.wildcards().isEmpty());
    }

    @Test
    @DisplayName("SELECT * is reported as a wildcard rather than a column")
    void bareStarIsAWildcard() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT * FROM sqlite_master");

        assertEquals(1, scanner.wildcards().size());
        assertTrue(scanner.columns().isEmpty());
    }

    @Test
    @DisplayName("a qualified star is reported as a wildcard")
    void qualifiedStarIsAWildcard() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT m.* FROM sqlite_master m");

        assertEquals(1, scanner.wildcards().size());
        assertTrue(scanner.columns().isEmpty());
    }

    @Test
    @DisplayName("count(*) is reported as a wildcard because its text contains a star")
    void countStarIsAlsoAWildcard() throws Exception {
        // Documents the actual rule: the check is expression.toString().contains("*"), so an aggregate
        // like count(*) lands in wildcards() rather than columns(). Conservative by design.
        DbQueryAstScanner scanner = scan("SELECT count(*) FROM sqlite_master");

        assertEquals(1, scanner.wildcards().size());
        assertTrue(scanner.columns().isEmpty());
    }

    @Test
    @DisplayName("a scalar subquery in the select list is descended into")
    void scalarSubqueryIsDescendedInto() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT (SELECT sql FROM sqlite_master LIMIT 1) FROM t");

        assertTrue(
                columnNames(scanner).contains("sql"),
                "the subquery's column must be collected or the allowlist is bypassable: " + columnNames(scanner));
    }

    @Test
    @DisplayName("a subquery in FROM is descended into")
    void fromSubqueryIsDescendedInto() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT x FROM (SELECT a AS x FROM inner_t) s");

        assertTrue(columnNames(scanner).contains("a"), show(columnNames(scanner)));
    }

    @Test
    @DisplayName("a subquery on the right of a JOIN is descended into")
    void joinSubqueryIsDescendedInto() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT t1.a FROM t1 JOIN (SELECT b FROM t2) j ON t1.id = j.b");

        List<String> names = columnNames(scanner);
        assertTrue(names.contains("b"), "joined subquery column missing: " + names);
        assertTrue(names.contains("t1.id"), "join ON column missing: " + names);
        assertTrue(names.contains("j.b"), "join ON column missing: " + names);
    }

    @Test
    @DisplayName("a UNION scans every branch")
    void unionScansEveryBranch() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT a FROM t1 UNION SELECT b FROM t2");

        List<String> names = columnNames(scanner);
        assertTrue(names.contains("a"), show(names));
        assertTrue(names.contains("b"), show(names));
    }

    @Test
    @DisplayName("a parenthesised branch of a UNION is scanned")
    void parenthesisedUnionBranchIsScanned() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT a FROM t1 UNION (SELECT b FROM t2)");

        List<String> names = columnNames(scanner);
        assertTrue(names.contains("a"), show(names));
        assertTrue(names.contains("b"), show(names));
    }

    @Test
    @DisplayName("WHERE, GROUP BY, HAVING and ORDER BY expressions are all visited")
    void clauseExpressionsAreVisited() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT a FROM t WHERE b = 1 GROUP BY c HAVING count(d) > 1 ORDER BY e");

        List<String> names = columnNames(scanner);
        assertTrue(names.contains("b"), "WHERE column missing: " + names);
        assertTrue(names.contains("c"), "GROUP BY column missing: " + names);
        assertTrue(names.contains("d"), "HAVING aggregate argument missing: " + names);
        assertTrue(names.contains("e"), "ORDER BY column missing: " + names);
    }

    @Test
    @DisplayName("an IN expression visits both sides")
    void inExpressionVisitsBothSides() throws Exception {
        DbQueryAstScanner withParameters = scan("SELECT a FROM t WHERE a IN (?, ?)");
        assertEquals(2, withParameters.jdbcParameters().size());

        DbQueryAstScanner withSubquery = scan("SELECT a FROM t WHERE a IN (SELECT b FROM t2)");
        assertTrue(columnNames(withSubquery).contains("b"), show(columnNames(withSubquery)));
    }

    @Test
    @DisplayName("a nested subquery two levels deep is still reached")
    void deeplyNestedSubqueryIsReached() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT * FROM (SELECT x FROM (SELECT y AS x FROM deep_t) inner_s) outer_s");

        assertTrue(columnNames(scanner).contains("y"), show(columnNames(scanner)));
    }

    @Test
    @DisplayName("the accessors expose the very lists that were populated")
    void accessorsExposePopulatedLists() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT name FROM sqlite_master WHERE type = ?");

        assertEquals(List.of("name", "type"), columnNames(scanner));
        assertEquals(1, scanner.jdbcParameters().size());
        assertEquals(0, scanner.wildcards().size());
    }

    @Test
    @DisplayName("an EXISTS subquery reaches the parenthesised-select visitor")
    void existsSubqueryIsDescendedInto() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT a FROM t WHERE EXISTS (SELECT 1 FROM t2 WHERE t2.a = t.a)");

        assertTrue(columnNames(scanner).contains("t2.a"), show(columnNames(scanner)));
    }

    @Test
    @DisplayName("a scalar subquery on the right of a comparison is descended into")
    void scalarSubqueryInComparisonIsDescendedInto() throws Exception {
        DbQueryAstScanner scanner = scan("SELECT a FROM t WHERE a = (SELECT max(b) FROM t2)");

        assertTrue(columnNames(scanner).contains("b"), show(columnNames(scanner)));
    }
}
