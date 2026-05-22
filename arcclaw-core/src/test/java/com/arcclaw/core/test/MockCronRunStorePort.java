package com.arcclaw.core.test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import com.arcclaw.persistence.api.CronRunDto;
import com.arcclaw.persistence.api.CronRunStorePort;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockCronRunStorePort implements CronRunStorePort {

    private final Map<String, CronRunDto> store = new ConcurrentHashMap<>();

    @Override
    public void save(CronRunDto dto) {
        store.put(dto.id(), dto);
    }

    @Override
    public Optional<CronRunDto> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<CronRunDto> findByCronId(String cronId, int offset, int limit) {
        List<CronRunDto> all = store.values().stream()
            .filter(r -> cronId.equals(r.cronId()))
            .sorted((a, b) -> {
                if (a.startedAt() == null && b.startedAt() == null) return 0;
                if (a.startedAt() == null) return 1;
                if (b.startedAt() == null) return -1;
                return b.startedAt().compareTo(a.startedAt());
            })
            .collect(Collectors.toList());
        int end = Math.min(offset + limit, all.size());
        if (offset >= all.size()) return List.of();
        return all.subList(offset, end);
    }

    @Override
    public long countByCronId(String cronId) {
        return store.values().stream().filter(r -> cronId.equals(r.cronId())).count();
    }

    @Override
    public List<CronRunDto> findAll(String status, int offset, int limit) {
        List<CronRunDto> all = store.values().stream()
            .filter(r -> status == null || status.isBlank() || status.equals(r.status()))
            .sorted((a, b) -> {
                if (a.startedAt() == null && b.startedAt() == null) return 0;
                if (a.startedAt() == null) return 1;
                if (b.startedAt() == null) return -1;
                return b.startedAt().compareTo(a.startedAt());
            })
            .collect(Collectors.toList());
        int end = Math.min(offset + limit, all.size());
        if (offset >= all.size()) return List.of();
        return all.subList(offset, end);
    }

    @Override
    public long countAll(String status) {
        if (status != null && !status.isBlank()) {
            return store.values().stream().filter(r -> status.equals(r.status())).count();
        }
        return store.size();
    }
}
