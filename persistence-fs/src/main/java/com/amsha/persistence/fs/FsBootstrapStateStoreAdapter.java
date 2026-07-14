package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsBootstrapStateStoreAdapter implements BootstrapStateStorePort {

    private static final String DOMAIN = "bootstrap";
    private static final String STATE_FILE = "bootstrap.json";
    private static final String UNCLAIMED = "UNCLAIMED";
    private static final String CREDENTIAL_ISSUED = "CREDENTIAL_ISSUED";

    @Inject
    FilesystemFileStore store;

    @Override
    public void save(BootstrapStateDto state) {
        if (getState().isEmpty()) {
            store.write(List.of(DOMAIN, STATE_FILE), state);
        }
    }

    @Override
    public boolean claimFirstOwner(String ownerId, String credentialDigest, Instant issuedAt) {
        Optional<BootstrapStateDto> existing = getState();
        if (existing.isPresent() && !UNCLAIMED.equals(existing.get().status())) {
            return false;
        }
        int nextRevision = existing.map(BootstrapStateDto::revision).orElse(0) + 1;
        BootstrapStateDto claimed = new BootstrapStateDto(
                "bootstrap", CREDENTIAL_ISSUED, ownerId, credentialDigest, issuedAt, null, nextRevision, issuedAt);
        store.write(List.of(DOMAIN, STATE_FILE), claimed);
        return true;
    }

    @Override
    public boolean confirmClaim(int expectedRevision, Instant claimedAt, Instant updatedAt) {
        Optional<BootstrapStateDto> existing = getState();
        if (existing.isEmpty()
                || !CREDENTIAL_ISSUED.equals(existing.get().status())
                || existing.get().revision() != expectedRevision) {
            return false;
        }
        BootstrapStateDto current = existing.get();
        BootstrapStateDto claimed = new BootstrapStateDto(
                "bootstrap",
                "CLAIMED",
                current.ownerId(),
                current.credentialDigest(),
                current.issuedAt(),
                claimedAt,
                current.revision() + 1,
                updatedAt);
        store.write(List.of(DOMAIN, STATE_FILE), claimed);
        return true;
    }

    @Override
    public boolean replaceCredential(int expectedRevision, String newDigest, Instant issuedAt, Instant updatedAt) {
        Optional<BootstrapStateDto> existing = getState();
        if (existing.isEmpty()
                || (!UNCLAIMED.equals(existing.get().status())
                        && !CREDENTIAL_ISSUED.equals(existing.get().status()))
                || existing.get().revision() != expectedRevision) {
            return false;
        }
        BootstrapStateDto current = existing.get();
        BootstrapStateDto updated = new BootstrapStateDto(
                "bootstrap",
                current.status(),
                current.ownerId(),
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
