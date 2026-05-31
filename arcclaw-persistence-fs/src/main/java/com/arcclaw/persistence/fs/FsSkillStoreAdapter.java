package com.arcclaw.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.SkillDto;
import com.arcclaw.persistence.api.SkillStorePort;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "fs")
@SuppressWarnings("java:S1192")
public class FsSkillStoreAdapter implements SkillStorePort {

    private static final String DOMAIN = "skills";
    private static final String BY_ID = "by-id";
    private static final String BY_NAME = "by-name";

    @Inject
    FilesystemFileStore store;

    @Override
    public void save(SkillDto dto) {
        store.write(List.of(DOMAIN, BY_ID, dto.id() + ".json"), dto);
        store.write(List.of(DOMAIN, BY_NAME, sanitizeName(dto.name()) + ".json"), dto);
    }

    @Override
    public List<SkillDto> findAll() {
        return store.list(List.of(DOMAIN, BY_ID), ".json").stream()
                .map(f -> store.read(List.of(DOMAIN, BY_ID, f), SkillDto.class))
                .toList();
    }

    @Override
    public Optional<SkillDto> findByName(String name) {
        return store.readOptional(List.of(DOMAIN, BY_NAME, sanitizeName(name) + ".json"), SkillDto.class);
    }

    @Override
    public boolean deleteById(String id) {
        String fileName = id + ".json";
        Optional<SkillDto> existing = store.readOptional(List.of(DOMAIN, BY_ID, fileName), SkillDto.class);
        if (existing.isPresent()) {
            store.delete(List.of(DOMAIN, BY_NAME, sanitizeName(existing.get().name()) + ".json"));
            store.delete(List.of(DOMAIN, BY_ID, fileName));
            return true;
        }
        return false;
    }

    @Override
    public long deleteAll() {
        List<String> files = store.list(List.of(DOMAIN, BY_ID), ".json");
        for (String f : files) {
            Optional<SkillDto> s = store.readOptional(List.of(DOMAIN, BY_ID, f), SkillDto.class);
            s.ifPresent(skill -> store.delete(List.of(DOMAIN, BY_NAME, sanitizeName(skill.name()) + ".json")));
            store.delete(List.of(DOMAIN, BY_ID, f));
        }
        return files.size();
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
