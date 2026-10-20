package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;

import org.jboss.logging.Logger;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Filesystem implementation of directive store.
 * Stores directives as JSON per user in {dataDir}/directives/{userId}.json.
 */
@ApplicationScoped
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsDirectiveStore implements DirectiveStorePort {

    private static final Logger log = Logger.getLogger(FsDirectiveStore.class);
    private static final String DOMAIN = "directives";

    record DirectiveBatch(List<SynthesizedDirective> directives) {}

    @Inject
    FilesystemFileStore store;

    @Override
    public int insert(SynthesizedDirective directive) {
        List<SynthesizedDirective> existing = loadDirectives(directive.userId());
        boolean exists = existing.stream()
                .anyMatch(d -> d.id().equals(directive.id())
                        || (d.userId().equals(directive.userId()) && d.text().equals(directive.text())));
        if (exists) {
            log.debugf("[SelfEvolve-FS] Directive already exists (dedup): %s", directive.id());
            return 0;
        }
        existing.add(directive);
        store.write(List.of(DOMAIN, directive.userId() + ".json"), new DirectiveBatch(existing));
        log.debugf("[SelfEvolve-FS] Persisted directive %s for user %s", directive.id(), directive.userId());
        return 1;
    }

    @Override
    public void update(SynthesizedDirective directive) {
        List<SynthesizedDirective> existing = loadDirectives(directive.userId());
        boolean found = false;
        for (int i = 0; i < existing.size(); i++) {
            if (existing.get(i).id().equals(directive.id())) {
                existing.set(i, directive);
                found = true;
                break;
            }
        }
        if (!found) {
            log.warnf("[SelfEvolve-FS] Directive %s not found for update", directive.id());
            return;
        }
        store.write(List.of(DOMAIN, directive.userId() + ".json"), new DirectiveBatch(existing));
    }

    @Override
    public List<SynthesizedDirective> getInjectable(String userId, int limit) {
        return loadDirectives(userId).stream()
                .filter(d -> d.state() == DirectiveState.ACTIVE || d.state() == DirectiveState.SHADOW)
                .sorted(Comparator.comparingDouble(SynthesizedDirective::confidence)
                        .reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<SynthesizedDirective> getAll(String userId, int limit) {
        List<SynthesizedDirective> all = loadDirectives(userId);
        all.sort(Comparator.comparing(SynthesizedDirective::createdAt).reversed());
        if (limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    @Override
    public SynthesizedDirective getById(String directiveId) {
        // Scan all user files to find by ID
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            for (SynthesizedDirective d : loadDirectivesFromFile(f)) {
                if (d.id().equals(directiveId)) {
                    return d;
                }
            }
        }
        return null;
    }

    @Override
    public List<SynthesizedDirective> getByState(DirectiveState state, int limit) {
        List<SynthesizedDirective> all = new ArrayList<>();
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            for (SynthesizedDirective d : loadDirectivesFromFile(f)) {
                if (d.state() == state) {
                    all.add(d);
                }
            }
        }
        all.sort(Comparator.comparingDouble(SynthesizedDirective::confidence).reversed());
        if (limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    @Override
    public boolean delete(String directiveId) {
        // Find and remove from user file
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            List<SynthesizedDirective> directives = loadDirectivesFromFile(f);
            boolean removed = directives.removeIf(d -> d.id().equals(directiveId));
            if (removed) {
                store.write(List.of(DOMAIN, f), new DirectiveBatch(directives));
                log.debugf("[SelfEvolve-FS] Deleted directive %s", directiveId);
                return true;
            }
        }
        return false;
    }

    @Override
    public int purgeExpired(int retentionDays) {
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        int purged = 0;
        for (String f : store.list(List.of(DOMAIN), ".json")) {
            List<SynthesizedDirective> directives = loadDirectivesFromFile(f);
            int before = directives.size();
            directives.removeIf(
                    d -> d.state() == DirectiveState.DISABLED && d.updatedAt().isBefore(cutoff));
            if (directives.size() < before) {
                store.write(List.of(DOMAIN, f), new DirectiveBatch(directives));
                purged += before - directives.size();
            }
        }
        return purged;
    }

    private List<SynthesizedDirective> loadDirectives(String userId) {
        return loadDirectivesFromFile(userId + ".json");
    }

    private List<SynthesizedDirective> loadDirectivesFromFile(String filename) {
        Optional<DirectiveBatch> batch = store.readOptional(List.of(DOMAIN, filename), DirectiveBatch.class);
        return batch.map(b -> new ArrayList<>(b.directives())).orElseGet(ArrayList::new);
    }
}
