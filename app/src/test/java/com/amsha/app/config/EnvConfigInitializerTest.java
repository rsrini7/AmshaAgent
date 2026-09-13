package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvConfigInitializerTest {
    @Test
    void patchEnvFileReportsStatesWithoutWritingSecretValues(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "AMSHA_SECRET_KEY=old-secret\nAMSHA_FEATURE=true\n");

        EnvConfigInitializer.patchEnvFile(
                envFile, "AMSHA_SECRET_KEY", "new-secret", EnvConfigInitializer.PatchMode.UPDATE);
        EnvConfigInitializer.patchEnvFile(
                envFile, "AMSHA_TOOLS_BRAVE_API_KEY", "none", EnvConfigInitializer.PatchMode.UPDATE);

        String content = Files.readString(envFile);
        assertTrue(content.contains("AMSHA_SECRET_KEY=new-secret"));
        assertTrue(content.contains("AMSHA_TOOLS_BRAVE_API_KEY=none"));

        Path changeLog = tempDir.resolve(".env.changes.log");
        String report = Files.readString(changeLog);
        assertTrue(report.contains("key=AMSHA_SECRET_KEY old=<set> new=<set>"));
        assertTrue(report.contains("key=AMSHA_TOOLS_BRAVE_API_KEY old=<unset> new=disabled"));
        assertTrue(report.contains("env=" + envFile.toAbsolutePath().normalize()));
        assertFalse(report.contains("old-secret"));
        assertFalse(report.contains("new-secret"));
    }

    @Test
    void patchEnvFileCreatesTimestampedBackupBeforeUpdating(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        String original = "AMSHA_FEATURE=false\n";
        Files.writeString(envFile, original);

        EnvConfigInitializer.patchEnvFile(envFile, "AMSHA_FEATURE", "true", EnvConfigInitializer.PatchMode.UPDATE);

        assertEquals("AMSHA_FEATURE=true\n", Files.readString(envFile));
        List<Path> backups;
        try (Stream<Path> files = Files.list(tempDir)) {
            backups = files.filter(path -> path.getFileName().toString().startsWith(".env.backup-"))
                    .toList();
        }
        assertEquals(1, backups.size());
        assertTrue(backups.getFirst().getFileName().toString().matches("\\.env\\.backup-\\d{8}-\\d{9}"));
        assertEquals(original, Files.readString(backups.getFirst()));
    }

    @Test
    void patchEnvFileDoesNotReportWhenUpdateMakesNoChange(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "AMSHA_FEATURE=true\n");

        EnvConfigInitializer.patchEnvFile(envFile, "AMSHA_FEATURE", "true", EnvConfigInitializer.PatchMode.UPDATE);

        assertFalse(Files.exists(tempDir.resolve(".env.changes.log")));
    }

    @Test
    void reportEnvFileCreatedRecordsPathAndSource(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");

        EnvConfigInitializer.reportEnvFileCreated(envFile, "bundled .env.example template");

        List<String> entries = Files.readAllLines(tempDir.resolve(".env.changes.log"));
        assertTrue(entries.getFirst().contains("event=created"));
        assertTrue(entries.getFirst().contains("source=bundled .env.example template"));
        assertTrue(
                entries.getFirst().contains(envFile.toAbsolutePath().normalize().toString()));
    }
}
