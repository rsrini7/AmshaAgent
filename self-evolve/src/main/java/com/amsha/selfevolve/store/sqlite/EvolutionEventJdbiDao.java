package com.amsha.selfevolve.store.sqlite;

import java.util.List;

import com.amsha.selfevolve.api.EvolutionEvent;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.locator.UseClasspathSqlLocator;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * JDBI SQL Object for evolution_events table (SQLite).
 */
@UseClasspathSqlLocator
public interface EvolutionEventJdbiDao {

    @SqlBatch("""
            INSERT OR IGNORE INTO evolution_events
            (event_id, session_id, user_id, event_type, role, content, tool_name, turn_number, metadata_json, created_at)
            VALUES (:eventId, :sessionId, :userId, :eventType, :role, :content, :toolName, :turnNumber, :metadataJson, :createdAt)
            """)
    int[] insertBatch(@BindBean List<EvolutionEvent> events);

    @SqlQuery("""
            SELECT event_id, session_id, user_id, event_type, role, content, tool_name, turn_number, metadata_json, created_at
            FROM evolution_events
            WHERE session_id = :sessionId
            ORDER BY created_at ASC
            LIMIT :limit
            """)
    List<EvolutionEvent> getBySession(@Bind("sessionId") String sessionId, @Bind("limit") int limit);

    @SqlQuery("""
            SELECT event_id, session_id, user_id, event_type, role, content, tool_name, turn_number, metadata_json, created_at
            FROM evolution_events
            WHERE user_id = :userId
            ORDER BY created_at ASC
            LIMIT :limit
            """)
    List<EvolutionEvent> getByUser(@Bind("userId") String userId, @Bind("limit") int limit);

    @SqlQuery("""
            DELETE FROM evolution_events
            WHERE created_at < datetime('now', '-' || :retentionDays || ' days')
            """)
    int purgeExpired(@Bind("retentionDays") int retentionDays);
}
