package com.amsha.selfevolve.store.noop;

import java.util.List;

import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.store.ExtractedMemoryStorePort;

/**
 * No-op implementation returned when self-evolve is disabled.
 */
public class NoOpExtractedMemoryStore implements ExtractedMemoryStorePort {

    @Override
    public int insertBatch(List<ExtractedMemory> memories) {
        return 0;
    }

    @Override
    public List<ExtractedMemory> getBySession(String sessionId, int limit) {
        return List.of();
    }

    @Override
    public List<ExtractedMemory> getByUser(String userId, int limit) {
        return List.of();
    }

    @Override
    public List<ExtractedMemory> getByType(MemoryRecordType recordType, int limit) {
        return List.of();
    }

    @Override
    public List<String> listUserIds(int limit) {
        return List.of();
    }

    @Override
    public boolean existsByHash(String sessionId, String contentHash) {
        return false;
    }

    @Override
    public int purgeExpired(int retentionDays) {
        return 0;
    }
}
