package com.arcclaw.core.skill;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.audit.TelemetryAuditService;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Watches the workspace skills directory for file changes and triggers
 * a hot-reload of the {@link SkillRegistry}.
 * <p>
 * Uses Java's {@link WatchService} on the {@code {workspace}/skills/} directory.
 * Debounces rapid events (e.g. editor save → create + modify) by coalescing
 * within a rolling 2-second window.
 */
@ApplicationScoped
public class SkillFileWatcher {

    private static final Logger LOG = Logger.getLogger(SkillFileWatcher.class);

    @Inject
    SkillLoader skillLoader;

    @Inject
    TelemetryAuditService telemetryAuditService;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread watcherThread;

    @PostConstruct
    void start() {
        Path skillsDir = Path.of(workspaceRootDir).resolve("skills");
        if (!skillsDir.toFile().exists()) {
            LOG.infof("Skills directory does not exist, watcher not started: %s", skillsDir);
            return;
        }

        running.set(true);
        watcherThread = Thread.ofPlatform().name("skill-watcher").daemon(true).start(() -> watchLoop(skillsDir));
        LOG.infof("Skill file watcher started on: %s", skillsDir);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }

    private void watchLoop(Path skillsDir) {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            skillsDir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);

            long lastReload = 0;
            while (running.get()) {
                WatchKey key;
                try {
                    key = watcher.poll(2000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (key == null) continue;

                boolean changed = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    var ev = (WatchEvent<Path>) event;
                    Path filename = ev.context();
                    if (filename.toString().endsWith(".md")) {
                        changed = true;
                    }
                }

                if (changed) {
                    long now = System.currentTimeMillis();
                    if (now - lastReload > 2000) {
                        lastReload = now;
                        reloadSkills();
                    }
                }

                if (!key.reset()) {
                    LOG.warn("Skills directory watch key no longer valid, stopping watcher");
                    break;
                }
            }
        } catch (IOException e) {
            LOG.warnf("Skill file watcher error: %s", e.getMessage());
        }
    }

    private void reloadSkills() {
        try {
            List<String> warnings = skillLoader.reloadAll(true);
            LOG.infof("Skill file watcher: reloaded skills (%d warnings)", warnings.size());
            telemetryAuditService.record(
                    AgentSentinels.SYSTEM_SESSION,
                    AgentSentinels.SYSTEM_USER,
                    AgentSentinels.SYSTEM_ROLE,
                    SecurityEventType.SKILL_REFRESHED,
                    "SkillFileWatcher: auto-reloaded from filesystem changes");
        } catch (Exception e) {
            LOG.errorf("Skill file watcher reload failed: %s", e.getMessage());
        }
    }
}
