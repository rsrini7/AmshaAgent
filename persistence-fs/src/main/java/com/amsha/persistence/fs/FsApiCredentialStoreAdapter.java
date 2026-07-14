package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsApiCredentialStoreAdapter implements ApiCredentialStorePort {

    private static final String DOMAIN = "api-credentials";

    @Inject
    FilesystemFileStore store;

    @Override
    public boolean save(ApiCredentialDto credential) {
        List<String> path = List.of(DOMAIN, credential.credentialId() + ".json");
        Optional<ApiCredentialDto> existing = store.readOptional(path, ApiCredentialDto.class);
        if (existing.isEmpty()) {
            store.write(path, credential);
            return true;
        }
        if (existing.get().revision() != credential.revision()) {
            return false;
        }
        ApiCredentialDto toWrite = new ApiCredentialDto(
                credential.credentialId(),
                credential.userId(),
                credential.keyDigest(),
                credential.roleSet(),
                credential.revision() + 1,
                credential.expiresAt(),
                credential.revokedAt(),
                credential.createdAt(),
                credential.updatedAt());
        store.write(path, toWrite);
        return true;
    }

    @Override
    public Optional<ApiCredentialDto> findById(String credentialId) {
        return store.readOptional(List.of(DOMAIN, credentialId + ".json"), ApiCredentialDto.class);
    }

    @Override
    public Optional<ApiCredentialDto> findByKeyDigest(String keyDigest) {
        return store.list(List.of(DOMAIN), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, file), ApiCredentialDto.class))
                .flatMap(Optional::stream)
                .filter(cred -> keyDigest.equals(cred.keyDigest()))
                .findFirst();
    }

    @Override
    public List<ApiCredentialDto> listByUserId(String userId) {
        return store.list(List.of(DOMAIN), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, file), ApiCredentialDto.class))
                .flatMap(Optional::stream)
                .filter(cred -> userId.equals(cred.userId()))
                .toList();
    }

    @Override
    public int deleteById(String credentialId) {
        boolean deleted = store.delete(List.of(DOMAIN, credentialId + ".json"));
        return deleted ? 1 : 0;
    }
}
