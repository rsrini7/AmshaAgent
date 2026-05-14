package com.arcclaw.persistence.fs;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.SecretDto;
import com.arcclaw.persistence.api.SecretStorePort;
import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class FsSecretStoreAdapter implements SecretStorePort {

    private static final String DOMAIN = "secrets";

    @Inject
    FilesystemFileStore store;

    @Override
    public void save(SecretDto dto) {
        store.write(List.of(DOMAIN, dto.sessionId(), dto.keyName() + ".json"), new SecretRecord(dto));
    }

    @Override
    public Optional<SecretDto> find(String sessionId, String keyName) {
        return store.readOptional(List.of(DOMAIN, sessionId, keyName + ".json"), SecretRecord.class)
            .map(SecretRecord::toDto);
    }

    @Override
    public void delete(String sessionId, String keyName) {
        store.delete(List.of(DOMAIN, sessionId, keyName + ".json"));
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        store.deleteDirectory(List.of(DOMAIN, sessionId));
    }

    private record SecretRecord(
        String sessionId,
        String keyName,
        int encryptionKeyVersion,
        String ciphertextBase64,
        String nonceBase64
    ) {
        SecretRecord(SecretDto dto) {
            this(
                dto.sessionId(),
                dto.keyName(),
                dto.encryptionKeyVersion(),
                java.util.Base64.getEncoder().encodeToString(dto.ciphertext()),
                java.util.Base64.getEncoder().encodeToString(dto.nonce())
            );
        }

        SecretDto toDto() {
            return new SecretDto(
                sessionId,
                keyName,
                encryptionKeyVersion,
                java.util.Base64.getDecoder().decode(ciphertextBase64),
                java.util.Base64.getDecoder().decode(nonceBase64)
            );
        }
    }
}
