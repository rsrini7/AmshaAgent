package com.arcclaw.persistence.sqlite;

import java.sql.SQLException;
import java.sql.Statement;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.flyway.FlywayConfigurationCustomizer;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jboss.logging.Logger;

/**
 * Drops FTS5 virtual tables before Flyway clean runs.
 * Flyway's SQLite clean sees FTS5 shadow tables (e.g., recall_messages_fts_data)
 * as regular tables and fails when trying to drop them directly.
 * Dropping the parent virtual table cascades removal of all shadow tables.
 */
@ApplicationScoped
public class FtsCleanCallback implements FlywayConfigurationCustomizer {

    private static final Logger log = Logger.getLogger(FtsCleanCallback.class);

    @Override
    public void customize(FluentConfiguration configuration) {
        configuration.callbacks(new BeforeCleanCallback());
    }

    private static class BeforeCleanCallback extends BaseCallback {

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.BEFORE_CLEAN;
        }

        @Override
        public void handle(Event event, Context context) {
            try (Statement stmt = context.getConnection().createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS recall_messages_fts");
            } catch (SQLException e) {
                log.debugf("FTS5 pre-clean drop skipped: %s", e.getMessage());
            }
        }
    }
}
