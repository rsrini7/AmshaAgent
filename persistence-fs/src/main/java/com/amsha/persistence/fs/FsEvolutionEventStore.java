package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.store.EvolutionEventStorePort;

import org.jboss.logging.Logger;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Filesystem implementation of evolution event store.
 * Stores events as JSON wrapper per session in {dataDir}/evolution/{sessionId}.json.
 */
@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsEvolutionEventStore implements EvolutionEventStorePort {

    private static final Logger log = Logger.getLogger(FsEvolutionEventStore.class);
    private static final String DOMAIN = "evolution";

    record EventBatch(List<EvolutionEvent> events) {}

    @Inject
    FilesystemFileStore store;

    @Override
    public int insertBatch(List<EvolutionEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        String sessionId = events.getFirst().sessionId();
        List<EvolutionEvent> existing = loadEvents(sessionId);
        existing.addAll(events);
        store.write(List.of(DOMAIN, sessionId + ".json"), new EventBatch(existing));
        log.debugf("[SelfEvolve-FS] Persisted %d events for session %s", events.size(), sessionId);
        return events.size();
    }

    @Override
    public List<EvolutionEvent> getBySession(String sessionId, int limit) {
        List<EvolutionEvent> events = loadEvents(sessionId);
        if (limit > 0 && events.size() > limit) {
            return events.subList(0, limit);
        }
        return events;
    }

    @Override
    public List<EvolutionEvent> getByUser(String userId, int limit) {
        List<EvolutionEvent> all = new ArrayList<>();
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            for (EvolutionEvent e : loadEventsFromFile(f)) {
                if (userId.equals(e.userId())) {
                    all.add(e);
                }
            }
        }
        all.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        if (limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    @Override
    public int purgeExpired(int retentionDays) {
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        int purged = 0;
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            List<EvolutionEvent> events = loadEventsFromFile(f);
            int before = events.size();
            events.removeIf(e -> e.createdAt().isBefore(cutoff));
            if (events.size() < before) {
                store.write(List.of(DOMAIN, f), new EventBatch(events));
                purged += before - events.size();
            }
        }
        return purged;
    }

    private List<EvolutionEvent> loadEvents(String sessionId) {
        return loadEventsFromFile(sessionId + ".json");
    }

    private List<EvolutionEvent> loadEventsFromFile(String filename) {
        Optional<EventBatch> batch = store.readOptional(List.of(DOMAIN, filename), EventBatch.class);
        return batch.map(b -> new ArrayList<>(b.events())).orElseGet(ArrayList::new);
    }
}
