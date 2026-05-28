package com.arcclaw.core.agent.reasoning.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.persistence.api.PromptDto;
import com.arcclaw.persistence.api.PromptStorePort;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class PromptRegistry {

    private static final Logger log = Logger.getLogger(PromptRegistry.class);

    @Inject
    Instance<PromptStorePort> storeInstance;

    private final Map<String, String> classpathCache = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent ev) {
        if (storeInstance.isUnsatisfied()) {
            log.info("No PromptStorePort implementation available — prompt registry is classpath-only");
            return;
        }
        syncFromClasspath();
    }

    private PromptStorePort store() {
        return storeInstance.get();
    }

    public String get(String roleId, String sectionKey) {
        return get(roleId, sectionKey, null);
    }

    public String get(String roleId, String sectionKey, String fallback) {
        if (storeInstance.isUnsatisfied()) {
            return classpathCache.getOrDefault(key(roleId, sectionKey), fallback);
        }
        try {
            Optional<PromptDto> opt = store().findActive(roleId, sectionKey);
            if (opt.isPresent()) {
                PromptDto dto = opt.get();
                if (dto.rolloutPercentage() < 100 && Math.random() * 100 >= dto.rolloutPercentage()) {
                    return classpathCache.getOrDefault(key(roleId, sectionKey), fallback);
                }
                if (!PromptConstants.STATUS_ACTIVE.equals(dto.status())) {
                    return classpathCache.getOrDefault(key(roleId, sectionKey), fallback);
                }
                return dto.content();
            }
        } catch (Exception e) {
            log.warnf("Prompt lookup failed for %s/%s: %s", roleId, sectionKey, LogUtil.rootMessage(e));
        }
        return classpathCache.getOrDefault(key(roleId, sectionKey), fallback);
    }

    public Optional<PromptDto> getDto(String roleId, String sectionKey) {
        if (storeInstance.isUnsatisfied()) return Optional.empty();
        try {
            return store().findActive(roleId, sectionKey);
        } catch (Exception e) {
            log.warnf("Prompt DTO lookup failed for %s/%s: %s", roleId, sectionKey, LogUtil.rootMessage(e));
            return Optional.empty();
        }
    }

    public Optional<PromptDto> getById(String id) {
        if (storeInstance.isUnsatisfied()) return Optional.empty();
        return store().findById(id);
    }

    public List<PromptDto> list() {
        if (storeInstance.isUnsatisfied()) return List.of();
        return store().findAll();
    }

    public List<PromptDto> listByRole(String roleId) {
        if (storeInstance.isUnsatisfied()) return List.of();
        return store().findByRole(roleId);
    }

    public void save(PromptDto dto) {
        if (storeInstance.isUnsatisfied()) return;
        store().save(dto);
        classpathCache.remove(key(dto.roleId(), dto.sectionKey()));
    }

    public boolean delete(String id) {
        if (storeInstance.isUnsatisfied()) return false;
        Optional<PromptDto> existing = store().findById(id);
        if (existing.isPresent()) {
            classpathCache.remove(key(existing.get().roleId(), existing.get().sectionKey()));
            return store().deleteById(id);
        }
        return false;
    }

    public void refresh() {
        log.info("PromptRegistry cache refreshed");
    }

    void syncFromClasspath() {
        long seedCount = 0;
        long driftCount = 0;

        for (ClasspathEntry entry : ALL_PERSONA_ENTRIES) {
            SyncResult r = syncEntry(entry.roleId, entry.sectionKey, entry.classpath);
            if (r == SyncResult.SEEDED) seedCount++;
            if (r == SyncResult.UPDATED) driftCount++;
        }
        for (ClasspathEntry entry : ALL_ROLE_TEMPLATE_ENTRIES) {
            SyncResult r = syncEntry(entry.roleId, entry.sectionKey, entry.classpath);
            if (r == SyncResult.SEEDED) seedCount++;
            if (r == SyncResult.UPDATED) driftCount++;
        }
        for (ClasspathEntry entry : ALL_SHARED_ENTRIES) {
            SyncResult r = syncEntry(entry.roleId, entry.sectionKey, entry.classpath);
            if (r == SyncResult.SEEDED) seedCount++;
            if (r == SyncResult.UPDATED) driftCount++;
        }

        if (seedCount > 0) {
            log.infof("Seeded %d new prompt entries from classpath", seedCount);
        }
        if (driftCount > 0) {
            log.infof("Detected drift in %d prompt entries — auto-updated from classpath", driftCount);
        }
        if (seedCount == 0 && driftCount == 0) {
            log.debug("Prompt registry is in sync with classpath — no action needed");
        }
    }

    private enum SyncResult {
        SEEDED,
        UPDATED,
        SKIPPED
    }

    private SyncResult syncEntry(String roleId, String sectionKey, String classpath) {
        String content = readClasspath(classpath);
        if (content == null) return SyncResult.SKIPPED;

        String hash = PromptHashUtil.sha256(content);
        String metadata = metadataJson(hash);

        Optional<PromptDto> existing = Optional.empty();
        try {
            existing = store().findActive(roleId, sectionKey);
        } catch (Exception e) {
            log.warnf("Error reading prompt %s/%s: %s", roleId, sectionKey, LogUtil.rootMessage(e));
        }

        if (existing.isEmpty()) {
            store().save(new PromptDto(
                    UUID.randomUUID().toString(),
                    roleId,
                    sectionKey,
                    content,
                    PromptConstants.DEFAULT_VERSION,
                    PromptConstants.STATUS_ACTIVE,
                    PromptConstants.DEFAULT_ROLLOUT,
                    metadata,
                    Instant.now(),
                    Instant.now()));
            classpathCache.put(key(roleId, sectionKey), content);
            return SyncResult.SEEDED;
        }

        PromptDto dto = existing.get();

        if (!PromptConstants.STATUS_ACTIVE.equals(dto.status()) || dto.version() > 1) {
            return SyncResult.SKIPPED;
        }

        String existingHash = PromptHashUtil.parseHash(dto.auditMetadata());
        if (hash.equals(existingHash)) {
            classpathCache.put(key(roleId, sectionKey), dto.content());
            return SyncResult.SKIPPED;
        }

        int newVersion = dto.version() + 1;
        store().save(new PromptDto(
                dto.id(),
                dto.roleId(),
                dto.sectionKey(),
                content,
                newVersion,
                PromptConstants.STATUS_ACTIVE,
                PromptConstants.DEFAULT_ROLLOUT,
                metadata,
                dto.createdAt(),
                Instant.now()));
        classpathCache.put(key(roleId, sectionKey), content);
        log.infof(
                "Drift detected in %s/%s (v%d->v%d): classpath content changed",
                roleId, sectionKey, dto.version(), newVersion);
        return SyncResult.UPDATED;
    }

    private static String metadataJson(String hash) {
        return "{\"source\":\"classpath\",\"sha256\":\"" + hash + "\"}";
    }

    private String readClasspath(String path) {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warnf("Failed to read classpath resource %s: %s", path, LogUtil.rootMessage(e));
            return null;
        }
    }

    private static String key(String roleId, String sectionKey) {
        return roleId + "/" + sectionKey;
    }

    private record ClasspathEntry(String roleId, String sectionKey, String classpath) {}

    private static final List<String> ALL_ROLE_IDS =
            AgentRoleEnum.values().stream().map(AgentRoleEnum::id).toList();

    private static final List<ClasspathEntry> ALL_PERSONA_ENTRIES;
    private static final List<ClasspathEntry> ALL_ROLE_TEMPLATE_ENTRIES;
    private static final List<ClasspathEntry> ALL_SHARED_ENTRIES;

    static {
        ALL_PERSONA_ENTRIES = ALL_ROLE_IDS.stream()
                .map(r ->
                        new ClasspathEntry(r, PromptConstants.KEY_PERSONA_IDENTITY, "templates/personas/" + r + ".txt"))
                .toList();

        ALL_ROLE_TEMPLATE_ENTRIES = ALL_ROLE_IDS.stream()
                .map(r -> new ClasspathEntry(
                        r, PromptConstants.KEY_ROLE_TEMPLATE, "templates/prompts/role-" + r + ".txt"))
                .toList();

        ALL_SHARED_ENTRIES = List.of(
                new ClasspathEntry(
                        PromptConstants.ROLE_CORE,
                        PromptConstants.KEY_DELEGATE_RULES,
                        "templates/prompts/delegate-to-agent-rules.txt"),
                new ClasspathEntry(
                        PromptConstants.ROLE_CORE,
                        PromptConstants.KEY_GITHUB_RULES,
                        "templates/prompts/github-tool-rules.txt"),
                new ClasspathEntry(
                        PromptConstants.ROLE_CORE,
                        PromptConstants.KEY_SANDBOX_RULES,
                        "templates/prompts/sandbox-exec-rules.txt"),
                new ClasspathEntry(
                        PromptConstants.ROLE_CORE,
                        PromptConstants.KEY_SAFETY_RULES,
                        "templates/prompts/safety-rules.txt"),
                new ClasspathEntry(
                        PromptConstants.ROLE_CORE,
                        PromptConstants.KEY_COMPACTION_SUMMARY,
                        "templates/prompts/compaction-summary.txt"));
    }
}
