package com.arcclaw.persistence.sqlite.jdbi;

import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.PromptDto;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(PromptDto.class)
public interface PromptJdbiDao {

    @SqlUpdate("INSERT INTO prompt_registry (id, role_id, section_key, content, version, status, rollout_percentage, audit_metadata, created_at, updated_at) VALUES (:id, :roleId, :sectionKey, :content, :version, :status, :rolloutPercentage, :auditMetadata, :createdAt, :updatedAt) ON CONFLICT(id) DO UPDATE SET content = EXCLUDED.content, version = EXCLUDED.version, status = EXCLUDED.status, rollout_percentage = EXCLUDED.rollout_percentage, audit_metadata = EXCLUDED.audit_metadata, updated_at = EXCLUDED.updated_at")
    void save(@BindMethods PromptDto dto);

    @SqlQuery("SELECT id, role_id AS roleId, section_key AS sectionKey, content, version, status, rollout_percentage AS rolloutPercentage, audit_metadata AS auditMetadata, created_at AS createdAt, updated_at AS updatedAt FROM prompt_registry")
    List<PromptDto> findAll();

    @SqlQuery("SELECT id, role_id AS roleId, section_key AS sectionKey, content, version, status, rollout_percentage AS rolloutPercentage, audit_metadata AS auditMetadata, created_at AS createdAt, updated_at AS updatedAt FROM prompt_registry WHERE id = :id")
    Optional<PromptDto> findById(@Bind("id") String id);

    @SqlQuery("SELECT id, role_id AS roleId, section_key AS sectionKey, content, version, status, rollout_percentage AS rolloutPercentage, audit_metadata AS auditMetadata, created_at AS createdAt, updated_at AS updatedAt FROM prompt_registry WHERE role_id = :roleId AND section_key = :sectionKey AND status = 'ACTIVE' ORDER BY version DESC LIMIT 1")
    Optional<PromptDto> findActive(@Bind("roleId") String roleId, @Bind("sectionKey") String sectionKey);

    @SqlQuery("SELECT id, role_id AS roleId, section_key AS sectionKey, content, version, status, rollout_percentage AS rolloutPercentage, audit_metadata AS auditMetadata, created_at AS createdAt, updated_at AS updatedAt FROM prompt_registry WHERE role_id = :roleId ORDER BY section_key, version DESC")
    List<PromptDto> findByRole(@Bind("roleId") String roleId);

    @SqlUpdate("DELETE FROM prompt_registry WHERE id = :id")
    boolean deleteById(@Bind("id") String id);

    @SqlUpdate("DELETE FROM prompt_registry WHERE role_id = :roleId AND section_key = :sectionKey")
    boolean deleteByRoleAndKey(@Bind("roleId") String roleId, @Bind("sectionKey") String sectionKey);

    @SqlUpdate("DELETE FROM prompt_registry")
    long deleteAll();

    @SqlQuery("SELECT COUNT(*) FROM prompt_registry")
    long count();
}
