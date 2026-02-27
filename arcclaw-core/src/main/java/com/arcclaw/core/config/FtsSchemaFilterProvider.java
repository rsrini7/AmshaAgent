package com.arcclaw.core.config;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

/**
 * Prevents Hibernate from crashing during startup when scanning SQLite FTS5 shadow tables.
 */
public class FtsSchemaFilterProvider implements SchemaFilterProvider {

    public static final FtsSchemaFilterProvider INSTANCE = new FtsSchemaFilterProvider();

    @Override
    public SchemaFilter getCreateFilter() { return IgnoreFtsFilter.INSTANCE; }

    @Override
    public SchemaFilter getDropFilter() { return IgnoreFtsFilter.INSTANCE; }

    @Override
    public SchemaFilter getMigrateFilter() { return IgnoreFtsFilter.INSTANCE; }

    @Override
    public SchemaFilter getValidateFilter() { return IgnoreFtsFilter.INSTANCE; }

    // Required for newer Hibernate 6.x versions
    @Override
    public SchemaFilter getTruncatorFilter() { return IgnoreFtsFilter.INSTANCE; }

    private static class IgnoreFtsFilter implements SchemaFilter {
        public static final IgnoreFtsFilter INSTANCE = new IgnoreFtsFilter();

        @Override
        public boolean includeNamespace(Namespace namespace) { return true; }

        @Override
        public boolean includeTable(Table table) {
            // SQLite creates shadow tables for FTS5 (e.g., _data, _idx, _docsize, _config).
            // Hibernate's StringTokenizer crashes when parsing their metadata.
            // This tells Hibernate to completely ignore them.
            return !table.getName().toLowerCase().contains("fts");
        }

        @Override
        public boolean includeSequence(Sequence sequence) { return true; }
    }
}