package com.arcclaw.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.PromptDto;
import com.arcclaw.persistence.api.PromptStorePort;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "fs")
@SuppressWarnings("java:S1192")
public class FsPromptStoreAdapter implements PromptStorePort {

    private static final String DOMAIN = "prompts";
    private static final String BY_ID = "by-id";
    private static final String BY_ROLE = "by-role";

    @Inject
    FilesystemFileStore store;

    @Override
    public void save(PromptDto dto) {
        store.write(List.of(DOMAIN, BY_ID, dto.id() + ".json"), dto);
        store.write(List.of(DOMAIN, BY_ROLE, dto.roleId(), dto.sectionKey() + ".json"), dto);
    }

    @Override
    public List<PromptDto> findAll() {
        return store.list(List.of(DOMAIN, BY_ID), ".json").stream()
                .map(f -> store.read(List.of(DOMAIN, BY_ID, f), PromptDto.class))
                .toList();
    }

    @Override
    public Optional<PromptDto> findById(String id) {
        return store.readOptional(List.of(DOMAIN, BY_ID, id + ".json"), PromptDto.class);
    }

    @Override
    public Optional<PromptDto> findActive(String roleId, String sectionKey) {
        return store.readOptional(List.of(DOMAIN, BY_ROLE, roleId, sectionKey + ".json"), PromptDto.class)
                .filter(dto -> "ACTIVE".equals(dto.status()));
    }

    @Override
    public List<PromptDto> findByRole(String roleId) {
        return store.list(List.of(DOMAIN, BY_ROLE, roleId), ".json").stream()
                .map(f -> store.read(List.of(DOMAIN, BY_ROLE, roleId, f), PromptDto.class))
                .toList();
    }

    @Override
    public boolean deleteById(String id) {
        Optional<PromptDto> existing = store.readOptional(List.of(DOMAIN, BY_ID, id + ".json"), PromptDto.class);
        if (existing.isPresent()) {
            store.delete(List.of(
                    DOMAIN, BY_ROLE, existing.get().roleId(), existing.get().sectionKey() + ".json"));
            store.delete(List.of(DOMAIN, BY_ID, id + ".json"));
            return true;
        }
        return false;
    }

    @Override
    public boolean deleteByRoleAndKey(String roleId, String sectionKey) {
        Optional<PromptDto> existing =
                store.readOptional(List.of(DOMAIN, BY_ROLE, roleId, sectionKey + ".json"), PromptDto.class);
        if (existing.isPresent()) {
            store.delete(List.of(DOMAIN, BY_ID, existing.get().id() + ".json"));
            store.delete(List.of(DOMAIN, BY_ROLE, roleId, sectionKey + ".json"));
            return true;
        }
        return false;
    }

    @Override
    public long deleteAll() {
        List<String> files = store.list(List.of(DOMAIN, BY_ID), ".json");
        for (String f : files) {
            Optional<PromptDto> p = store.readOptional(List.of(DOMAIN, BY_ID, f), PromptDto.class);
            p.ifPresent(
                    prompt -> store.delete(List.of(DOMAIN, BY_ROLE, prompt.roleId(), prompt.sectionKey() + ".json")));
            store.delete(List.of(DOMAIN, BY_ID, f));
        }
        return files.size();
    }

    @Override
    public long count() {
        return store.list(List.of(DOMAIN, BY_ID), ".json").size();
    }
}
