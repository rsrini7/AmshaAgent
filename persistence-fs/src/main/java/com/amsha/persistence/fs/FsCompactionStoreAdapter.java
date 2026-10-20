package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.persistence.api.CompactionStorePort;
import com.amsha.persistence.api.CompactionSummaryDto;
import com.amsha.persistence.api.PersistenceBackend;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsCompactionStoreAdapter implements CompactionStorePort {

    private static final String DOMAIN = "compaction";

    @Inject
    FilesystemFileStore store;

    @Override
    public Optional<CompactionSummaryDto> findLatestBySessionId(String sessionId) {
        return store.readOptional(List.of(DOMAIN, sessionId + ".json"), CompactionSummaryDto.class);
    }

    @Override
    public void upsert(CompactionSummaryDto dto) {
        store.write(List.of(DOMAIN, dto.sessionId() + ".json"), dto);
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        store.delete(List.of(DOMAIN, sessionId + ".json"));
    }

    @Override
    public List<CompactionSummaryDto> search(String userId, String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT);
        return store.list(List.of(DOMAIN), ".json").stream()
                .map(f -> store.readOptional(List.of(DOMAIN, f), CompactionSummaryDto.class))
                .flatMap(Optional::stream)
                .filter(dto -> userId == null || userId.equals(dto.userId()))
                .filter(dto -> dto.summary() != null
                        && dto.summary().toLowerCase(Locale.ROOT).contains(q))
                .limit(limit)
                .toList();
    }
}
