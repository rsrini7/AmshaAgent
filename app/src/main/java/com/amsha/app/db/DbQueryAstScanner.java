package com.amsha.app.db;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursively walks a SELECT AST collecting every referenced column, every wildcard, and every
 * user-supplied JDBC placeholder.
 *
 * <p>{@code ExpressionVisitorAdapter} does not descend into nested selects, so without this
 * recursion a scalar subquery such as {@code SELECT (SELECT sql FROM sqlite_master LIMIT 1)}
 * reports zero columns and silently bypasses the schema-metadata column allowlist.
 */
public final class DbQueryAstScanner extends ExpressionVisitorAdapter {

    private final List<Column> columns = new ArrayList<>();
    private final List<JdbcParameter> jdbcParameters = new ArrayList<>();
    private final List<SelectItem<?>> wildcards = new ArrayList<>();

    private DbQueryAstScanner() {}

    /** Scans {@code select} and every nested select it contains. */
    public static DbQueryAstScanner scan(Select select) {
        DbQueryAstScanner scanner = new DbQueryAstScanner();
        scanner.visitSelect(select);
        return scanner;
    }

    public List<Column> columns() {
        return columns;
    }

    public List<JdbcParameter> jdbcParameters() {
        return jdbcParameters;
    }

    public List<SelectItem<?>> wildcards() {
        return wildcards;
    }

    @Override
    public void visit(Column column) {
        columns.add(column);
    }

    @Override
    public void visit(JdbcParameter parameter) {
        jdbcParameters.add(parameter);
    }

    @Override
    public void visit(ParenthesedSelect parenthesedSelect) {
        if (parenthesedSelect != null && parenthesedSelect.getSelect() != null) {
            visitSelect(parenthesedSelect.getSelect());
        }
    }

    @Override
    public void visit(Select select) {
        visitSelect(select);
    }

    @Override
    public void visit(InExpression expression) {
        if (expression.getLeftExpression() != null) {
            expression.getLeftExpression().accept(this);
        }
        if (expression.getRightExpression() != null) {
            expression.getRightExpression().accept(this);
        }
    }

    private void visitSelect(Select select) {
        if (select == null) {
            return;
        }
        if (select instanceof SetOperationList setOperations) {
            if (setOperations.getSelects() != null) {
                for (Select nested : setOperations.getSelects()) {
                    visitSelect(nested);
                }
            }
            return;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            if (parenthesedSelect.getSelect() != null) {
                visitSelect(parenthesedSelect.getSelect());
            }
            return;
        }
        if (select instanceof PlainSelect plainSelect) {
            scanBody(plainSelect);
        }
    }

    private void scanBody(PlainSelect plainSelect) {
        if (plainSelect == null) {
            return;
        }
        if (plainSelect.getFromItem() instanceof ParenthesedSelect pSel && pSel.getSelect() != null) {
            visitSelect(pSel.getSelect());
        }
        if (plainSelect.getSelectItems() != null) {
            for (SelectItem<?> item : plainSelect.getSelectItems()) {
                Expression expression = item.getExpression();
                if (expression == null
                        || expression instanceof AllColumns
                        || expression instanceof AllTableColumns
                        || expression.toString().contains("*")) {
                    wildcards.add(item);
                } else {
                    expression.accept(this);
                }
            }
        }
        accept(plainSelect.getWhere());
        accept(plainSelect.getHaving());
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                if (join.getRightItem() instanceof ParenthesedSelect pSel && pSel.getSelect() != null) {
                    visitSelect(pSel.getSelect());
                }
                if (join.getOnExpression() != null) {
                    join.getOnExpression().accept(this);
                }
            }
        }
        if (plainSelect.getOrderByElements() != null) {
            for (OrderByElement element : plainSelect.getOrderByElements()) {
                if (element.getExpression() != null) {
                    element.getExpression().accept(this);
                }
            }
        }
        if (plainSelect.getGroupBy() != null && plainSelect.getGroupBy().getGroupByExpressionList() != null) {
            for (Object groupExpr : plainSelect.getGroupBy().getGroupByExpressionList()) {
                if (groupExpr instanceof Expression expression) {
                    expression.accept(this);
                }
            }
        }
    }

    private void accept(Expression expression) {
        if (expression != null) {
            expression.accept(this);
        }
    }
}
