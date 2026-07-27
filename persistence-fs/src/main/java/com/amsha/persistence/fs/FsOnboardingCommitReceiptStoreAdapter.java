package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingCommitReceiptStorePort;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsOnboardingCommitReceiptStoreAdapter implements OnboardingCommitReceiptStorePort {

    private static final String DOMAIN = "onboarding-receipts";

    @Inject
    FilesystemFileStore store;

    private List<String> receiptPath(String operationId) {
        return List.of(DOMAIN, operationId + ".json");
    }

    private List<String> receiptDir() {
        return List.of(DOMAIN);
    }

    @Override
    public synchronized void upsert(OnboardingCommitReceiptDto receipt) {
        store.write(receiptPath(receipt.operationId()), receipt);
    }

    @Override
    public synchronized Optional<OnboardingCommitReceiptDto> findByOperationId(String operationId) {
        return store.readOptional(receiptPath(operationId), OnboardingCommitReceiptDto.class);
    }

    @Override
    public synchronized List<OnboardingCommitReceiptDto> listByDraftId(String draftId) {
        return store.list(receiptDir(), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, file), OnboardingCommitReceiptDto.class))
                .flatMap(Optional::stream)
                .filter(r -> draftId.equals(r.draftId()))
                .toList();
    }

    @Override
    public synchronized int deleteByDraftId(String draftId) {
        List<OnboardingCommitReceiptDto> receipts = listByDraftId(draftId);
        int deleted = 0;
        for (OnboardingCommitReceiptDto r : receipts) {
            if (store.delete(receiptPath(r.operationId()))) deleted++;
        }
        return deleted;
    }
}
