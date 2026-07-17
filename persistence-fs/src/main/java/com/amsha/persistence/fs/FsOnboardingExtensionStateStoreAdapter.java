package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingExtensionStateDto;
import com.amsha.persistence.api.OnboardingExtensionStateStorePort;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsOnboardingExtensionStateStoreAdapter implements OnboardingExtensionStateStorePort {

    private static final String DOMAIN = "onboarding";
    private static final String EXT_DIR = "extensions";

    @Inject
    FilesystemFileStore store;

    private String fileName(String draftId, String extensionId) {
        return draftId + "_" + extensionId + ".json";
    }

    @Override
    public synchronized List<OnboardingExtensionStateDto> listByDraft(String draftId) {
        List<OnboardingExtensionStateDto> result = new ArrayList<>();
        File dir = store.resolve(List.of(DOMAIN, EXT_DIR)).toFile();
        if (dir.isDirectory()) {
            for (File f : dir.listFiles((d, n) -> n.startsWith(draftId + "_") && n.endsWith(".json"))) {
                store.readOptional(List.of(DOMAIN, EXT_DIR, f.getName()), OnboardingExtensionStateDto.class)
                        .ifPresent(result::add);
            }
        }
        return result;
    }

    @Override
    public synchronized Optional<OnboardingExtensionStateDto> findByDraftAndExtension(
            String draftId, String extensionId) {
        return store.readOptional(
                List.of(DOMAIN, EXT_DIR, fileName(draftId, extensionId)), OnboardingExtensionStateDto.class);
    }

    @Override
    public synchronized boolean save(OnboardingExtensionStateDto state) {
        store.write(List.of(DOMAIN, EXT_DIR, fileName(state.draftId(), state.extensionId())), state);
        return true;
    }

    @Override
    public synchronized boolean update(OnboardingExtensionStateDto state, int expectedRevisionUnused) {
        store.write(List.of(DOMAIN, EXT_DIR, fileName(state.draftId(), state.extensionId())), state);
        return true;
    }

    @Override
    public synchronized int deleteByDraft(String draftId) {
        int removed = 0;
        File dir = store.resolve(List.of(DOMAIN, EXT_DIR)).toFile();
        if (dir.isDirectory()) {
            for (File f : dir.listFiles((d, n) -> n.startsWith(draftId + "_") && n.endsWith(".json"))) {
                if (f.delete()) removed++;
            }
        }
        return removed;
    }
}
