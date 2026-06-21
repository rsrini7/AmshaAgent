package com.amsha.persistence.api;

import java.util.List;
import java.util.Optional;

public interface PromptStorePort {
    void save(PromptDto dto);

    List<PromptDto> findAll();

    Optional<PromptDto> findById(String id);

    Optional<PromptDto> findActive(String roleId, String sectionKey);

    List<PromptDto> findByRole(String roleId);

    boolean deleteById(String id);

    boolean deleteByRoleAndKey(String roleId, String sectionKey);

    long deleteAll();

    long count();
}
