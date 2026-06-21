package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.CronRunStorePort;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsCronRunStoreAdapter implements CronRunStorePort {

    private static final Logger log = Logger.getLogger(FsCronRunStoreAdapter.class);
    private static final String DOMAIN = "cron-runs";

    @Inject
    FilesystemFileStore store;

    @Override
    public void save(CronRunDto dto) {
        String fileName = dto.id() + ".json";
        log.debugf("Saving cron run %s for cron %s", dto.id(), dto.cronId());
        store.write(List.of(DOMAIN, dto.cronId(), fileName), dto);
    }

    @Override
    public Optional<CronRunDto> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        List<String> cronDirs = store.listDirs(List.of(DOMAIN));
        for (String cronId : cronDirs) {
            Optional<CronRunDto> found = store.readOptional(List.of(DOMAIN, cronId, id + ".json"), CronRunDto.class);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    @Override
    public List<CronRunDto> findByCronId(String cronId, int offset, int limit) {
        List<CronRunDto> all = loadByCronId(cronId);
        all = all.stream()
                .sorted((a, b) -> {
                    if (a.startedAt() == null && b.startedAt() == null) return 0;
                    if (a.startedAt() == null) return 1;
                    if (b.startedAt() == null) return -1;
                    return b.startedAt().compareTo(a.startedAt());
                })
                .toList();
        int end = Math.min(offset + limit, all.size());
        if (offset >= all.size()) return List.of();
        return all.subList(offset, end);
    }

    @Override
    public long countByCronId(String cronId) {
        return loadByCronId(cronId).size();
    }

    @Override
    public List<CronRunDto> findAll(String status, int offset, int limit) {
        List<CronRunDto> results = new ArrayList<>();
        List<String> cronDirs = store.listDirs(List.of(DOMAIN));
        for (String cronId : cronDirs) {
            results.addAll(loadByCronId(cronId));
        }
        if (status != null && !status.isBlank()) {
            results = results.stream().filter(r -> status.equals(r.status())).toList();
        }
        results = results.stream()
                .sorted((a, b) -> {
                    if (a.startedAt() == null && b.startedAt() == null) return 0;
                    if (a.startedAt() == null) return 1;
                    if (b.startedAt() == null) return -1;
                    return b.startedAt().compareTo(a.startedAt());
                })
                .toList();
        int end = Math.min(offset + limit, results.size());
        if (offset >= results.size()) return List.of();
        return results.subList(offset, end);
    }

    @Override
    public long countAll(String status) {
        return findAll(status, 0, Integer.MAX_VALUE).size();
    }

    private List<CronRunDto> loadByCronId(String cronId) {
        List<CronRunDto> runs = new ArrayList<>();
        List<String> files = store.list(List.of(DOMAIN, cronId), ".json");
        for (String file : files) {
            store.readOptional(List.of(DOMAIN, cronId, file), CronRunDto.class).ifPresent(runs::add);
        }
        return runs;
    }
}
