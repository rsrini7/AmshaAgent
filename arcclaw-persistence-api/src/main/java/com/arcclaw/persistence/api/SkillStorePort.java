package com.arcclaw.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Port for persisting and managing agent skills.
 * §1.2: Core-persistence boundary.
 */
public interface SkillStorePort {
    void save(SkillDto dto);

    List<SkillDto> findAll();

    Optional<SkillDto> findByName(String name);

    boolean deleteById(String id);

    long deleteAll();
}
