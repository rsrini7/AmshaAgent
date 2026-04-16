package com.arcclaw.persistence.sqlite;

import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.community.dialect.identity.SQLiteIdentityColumnSupport;
import org.hibernate.dialect.identity.IdentityColumnSupport;

/**
 * Extends the community SQLiteDialect to fix a Hibernate ORM 7 DDL regression.
 *
 * In ORM 7, hasDataTypeInIdentityColumn() = false causes the DDL exporter to
 * emit a blank type for IDENTITY columns (e.g. "checkpoint_id," with no type).
 * SQLite requires exactly "INTEGER PRIMARY KEY" to activate the rowid alias.
 * This subclass overrides that flag to force the type into the DDL output.
 * 
 * Encapsulated inside arcclaw-persistence-sqlite adapter module.
 * Used in Hibernate configuration via spring.jpa.database-platform property.
 */
public class ArcClawSQLiteDialect extends SQLiteDialect {

    private static final IdentityColumnSupport IDENTITY_SUPPORT =
            new SQLiteIdentityColumnSupport() {
                @Override
                public boolean hasDataTypeInIdentityColumn() {
                    return false; // Force 'integer' as the base for the rowid alias
                }

                @Override
                public String getIdentityColumnString(int type) {
                    return "integer"; // Hibernate will add the primary key constraint at table level
                }
            };

    @Override
    public IdentityColumnSupport getIdentityColumnSupport() {
        return IDENTITY_SUPPORT;
    }
}
