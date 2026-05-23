package com.arcclaw.persistence.sqlite.jdbi;

import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.SkillDto;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(SkillDto.class)
public interface SkillJdbiDao {

    @SqlUpdate("INSERT OR REPLACE INTO agent_skill (id, name, definitionYaml, createdAt, updatedAt) VALUES (:id, :name, :definitionYaml, :createdAt, :updatedAt)")
    void save(@BindMethods SkillDto dto);

    @SqlQuery("SELECT id, name, definitionYaml, createdAt, updatedAt FROM agent_skill WHERE id = :id")
    Optional<SkillDto> findById(@Bind("id") String id);

    @SqlQuery("SELECT id, name, definitionYaml, createdAt, updatedAt FROM agent_skill")
    List<SkillDto> findAll();

    @SqlQuery("SELECT id, name, definitionYaml, createdAt, updatedAt FROM agent_skill WHERE name = :name")
    Optional<SkillDto> findByName(@Bind("name") String name);

    @SqlUpdate("DELETE FROM agent_skill WHERE id = :id")
    boolean deleteById(@Bind("id") String id);

    @SqlUpdate("DELETE FROM agent_skill")
    long deleteAll();
}
