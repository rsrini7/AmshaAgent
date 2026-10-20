package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.PersistenceBackend;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsBootstrapStateStoreAdapter implements BootstrapStateStorePort {

    private static final String DOMAIN = "bootstrap";
    private static final String STATE_FILE = "bootstrap.json";

    @Inject
    FilesystemFileStore store;

    @Override
    public void save(BootstrapStateDto state) {
        if (getState().isEmpty()) {
            store.write(List.of(DOMAIN, STATE_FILE), state);
        }
    }

    @Override
    public synchronized Optional<Integer> reserveFirstOwner(String ownerId, String workspaceId, Instant reservedAt) {
        Optional<BootstrapStateDto> existing = getState();
        if (existing.isPresent() && !existing.get().isUnclaimed()) {
            return Optional.empty();
        }
        int nextRevision = existing.map(BootstrapStateDto::revision).orElse(0) + 1;
        BootstrapStateDto reserved = new BootstrapStateDto(
                "bootstrap",
                BootstrapStateDto.STATUS_CLAIMING,
                ownerId,
                workspaceId,
                null,
                null,
                null,
                nextRevision,
                reservedAt);
        store.write(List.of(DOMAIN, STATE_FILE), reserved);
        return Optional.of(nextRevision);
    }

    @Override
    public synchronized boolean recordCredentialIssued(
            int expectedRevision, String credentialDigest, Instant issuedAt) {
        Optional<BootstrapStateDto> existing = getState();
        if (existing.isEmpty() || !existing.get().isClaiming() || existing.get().revision() != expectedRevision) {
            return false;
        }
        BootstrapStateDto current = existing.get();
        BootstrapStateDto issued = new BootstrapStateDto(
                "bootstrap",
                BootstrapStateDto.STATUS_CREDENTIAL_ISSUED,
                current.ownerId(),
                current.workspaceId(),
                credentialDigest,
                issuedAt,
                null,
                current.revision() + 1,
                issuedAt);
        store.write(List.of(DOMAIN, STATE_FILE), issued);
        return true;
    }

    @Override
    public synchronized boolean confirmClaim(int expectedRevision, String authenticatedOwnerId, Instant claimedAt) {
        Optional<BootstrapStateDto> existing = getState();
        if (existing.isEmpty()
                || !existing.get().isCredentialIssued()
                || existing.get().revision() != expectedRevision
                || !authenticatedOwnerId.equals(existing.get().ownerId())) {
            return false;
        }
        BootstrapStateDto current = existing.get();
        BootstrapStateDto claimed = new BootstrapStateDto(
                "bootstrap",
                BootstrapStateDto.STATUS_CLAIMED,
                current.ownerId(),
                current.workspaceId(),
                current.credentialDigest(),
                current.issuedAt(),
                claimedAt,
                current.revision() + 1,
                claimedAt);
        store.write(List.of(DOMAIN, STATE_FILE), claimed);
        return true;
    }

    @Override
    public synchronized boolean rollbackClaiming(int expectedRevision, Instant rolledBackAt) {
        Optional<BootstrapStateDto> existing = getState();
        if (existing.isEmpty() || !existing.get().isClaiming() || existing.get().revision() != expectedRevision) {
            return false;
        }
        BootstrapStateDto reset = new BootstrapStateDto(
                "bootstrap",
                BootstrapStateDto.STATUS_UNCLAIMED,
                null,
                null,
                null,
                null,
                null,
                existing.get().revision() + 1,
                rolledBackAt);
        store.write(List.of(DOMAIN, STATE_FILE), reset);
        return true;
    }

    @Override
    public synchronized boolean replaceCredential(
            int expectedRevision, String newDigest, Instant issuedAt, Instant updatedAt) {
        Optional<BootstrapStateDto> existing = getState();
        if (existing.isEmpty()
                || (!existing.get().isCredentialIssued()
                        && !existing.get().isClaiming()
                        && !existing.get().isClaimed())
                || existing.get().revision() != expectedRevision) {
            return false;
        }
        BootstrapStateDto current = existing.get();
        BootstrapStateDto updated = new BootstrapStateDto(
                "bootstrap",
                current.status(),
                current.ownerId(),
                current.workspaceId(),
                newDigest,
                issuedAt,
                current.claimedAt(),
                current.revision() + 1,
                updatedAt);
        store.write(List.of(DOMAIN, STATE_FILE), updated);
        return true;
    }

    @Override
    public Optional<BootstrapStateDto> getState() {
        return store.readOptional(List.of(DOMAIN, STATE_FILE), BootstrapStateDto.class);
    }
}
