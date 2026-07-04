package com.amsha.selfevolve.store.postgres;

import java.util.List;

import com.amsha.selfevolve.api.SynthesizedDirective;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SQL Object for synthesized_directives table (PostgreSQL).
 */
@RegisterConstructorMapper(SynthesizedDirective.class)
public interface PostgresDirectiveJdbiDao {

    @SqlUpdate("""
            INSERT INTO synthesized_directives
            (id, user_id, text, state, confidence, source_record_ids, synthesis_prompt_hash,
             evaluation_hits, evaluation_misses, created_at, updated_at)
            VALUES (:id, :userId, :text, :state, :confidence, :sourceRecordIdsJson,
                    :synthesisPromptHash, :evaluationHits, :evaluationMisses, :createdAt, :updatedAt)
            ON CONFLICT (user_id, text) DO NOTHING
            """)
    int insert(@BindBean SynthesizedDirective directive);

    @SqlUpdate("""
            UPDATE synthesized_directives
            SET state = :state, confidence = :confidence,
                evaluation_hits = :evaluationHits, evaluation_misses = :evaluationMisses,
                updated_at = :updatedAt
            WHERE id = :id
            """)
    int update(@BindBean SynthesizedDirective directive);

    @SqlQuery("""
            SELECT id, user_id, text, state, confidence, source_record_ids, synthesis_prompt_hash,
                   evaluation_hits, evaluation_misses, created_at, updated_at
            FROM synthesized_directives
            WHERE user_id = :userId AND state IN ('ACTIVE', 'SHADOW')
            ORDER BY confidence DESC
            LIMIT :limit
            """)
    List<SynthesizedDirective> getInjectable(@Bind("userId") String userId, @Bind("limit") int limit);

    @SqlQuery("""
            SELECT id, user_id, text, state, confidence, source_record_ids, synthesis_prompt_hash,
                   evaluation_hits, evaluation_misses, created_at, updated_at
            FROM synthesized_directives
            WHERE user_id = :userId
            ORDER BY created_at DESC
            LIMIT :limit
            """)
    List<SynthesizedDirective> getAll(@Bind("userId") String userId, @Bind("limit") int limit);

    @SqlQuery("""
            SELECT id, user_id, text, state, confidence, source_record_ids, synthesis_prompt_hash,
                   evaluation_hits, evaluation_misses, created_at, updated_at
            FROM synthesized_directives
            WHERE id = :id
            """)
    SynthesizedDirective getById(@Bind("id") String directiveId);

    @SqlQuery("""
            SELECT id, user_id, text, state, confidence, source_record_ids, synthesis_prompt_hash,
                   evaluation_hits, evaluation_misses, created_at, updated_at
            FROM synthesized_directives
            WHERE state = :state
            ORDER BY confidence DESC
            LIMIT :limit
            """)
    List<SynthesizedDirective> getByState(@Bind("state") String state, @Bind("limit") int limit);

    @SqlUpdate("DELETE FROM synthesized_directives WHERE id = :id")
    int deleteById(@Bind("id") String directiveId);

    @SqlUpdate("""
            DELETE FROM synthesized_directives
            WHERE state = 'DISABLED' AND updated_at < NOW() - CAST(:retentionDays || ' days' AS INTERVAL)
            """)
    int purgeExpired(@Bind("retentionDays") int retentionDays);
}
