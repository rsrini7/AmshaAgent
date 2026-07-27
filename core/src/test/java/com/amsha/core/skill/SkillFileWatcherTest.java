package com.amsha.core.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.amsha.core.audit.TelemetryAuditService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillFileWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void ignoresMissingDirectoryAndStopsSafely() {
        SkillFileWatcher watcher = watcher(tempDir.resolve("missing"));
        assertDoesNotThrow(watcher::start);
        assertDoesNotThrow(watcher::stop);
    }

    @Test
    void reloadsWhenMarkdownChangesAndStopsWatcherThread() throws Exception {
        Path skills = Files.createDirectories(tempDir.resolve("skills"));
        SkillFileWatcher watcher = watcher(tempDir);
        when(watcher.skillLoader.reloadAll(true)).thenReturn(List.of());

        watcher.start();
        Thread.sleep(100);
        Files.writeString(skills.resolve("new-skill.md"), "content");
        verify(watcher.skillLoader, timeout(5000)).reloadAll(true);
        verify(watcher.telemetryAuditService, timeout(1000))
                .record(anyString(), anyString(), anyString(), any(), anyString());
        watcher.stop();
    }

    private static SkillFileWatcher watcher(Path root) {
        SkillFileWatcher watcher = new SkillFileWatcher();
        watcher.workspaceRootDir = root.toString();
        watcher.skillLoader = mock(SkillLoader.class);
        watcher.telemetryAuditService = mock(TelemetryAuditService.class);
        return watcher;
    }
}
