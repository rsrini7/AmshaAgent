package com.arcclaw.persistence.fs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.BackendCapabilities;
import com.arcclaw.persistence.api.IngestResult;
import com.arcclaw.persistence.api.MemoryChunkDto;
import com.arcclaw.persistence.api.MemoryEntryDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;

@ApplicationScoped
public class FsMemoryRetrievalAdapter implements MemoryRetrievalPort {

    private static final String DOMAIN = "memory-index";

    @Inject
    FilesystemFileStore store;

    private final Map<String, String> textIndex = Collections.synchronizedMap(new HashMap<>());

    @PostConstruct
    void loadExistingIndex() {
        List<String> files = store.list(List.of(DOMAIN), ".json");
        for (String f : files) {
            String id = f.replace(".json", "");
            store.readOptional(List.of(DOMAIN, f), IndexEntry.class)
                .ifPresent(entry -> textIndex.put(id, entry.text));
        }
    }

    @Override
    public List<MemoryChunkDto> search(String query, int topK, String userId) {
        String q = query.toLowerCase();
        List<MemoryChunkDto> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : textIndex.entrySet()) {
            if (entry.getValue() != null && entry.getValue().toLowerCase().contains(q)) {
                results.add(new MemoryChunkDto(entry.getKey(), entry.getValue(), 1.0));
            }
        }
        int limit = Math.min(topK, results.size());
        return results.subList(0, limit);
    }

    @Override
    public IngestResult index(MemoryEntryDto entry) {
        String id = entry.id() != null ? entry.id() : UUID.randomUUID().toString();
        String text = entry.text();
        textIndex.put(id, text);
        store.write(List.of(DOMAIN, id + ".json"), new IndexEntry(id, text));
        return new IngestResult(id, true);
    }

    @Override
    public BackendCapabilities capabilities() {
        return new BackendCapabilities(
            true,
            false,
            false,
            false
        );
    }

    @SuppressWarnings("unused")
    private record IndexEntry(String id, String text) {}
}
