package com.amsha.core.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.amsha.common.util.PathSanitizer;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class WorkspacePathSafetyTest {

    @Inject
    WorkspaceManager workspaceManager;

    @ConfigProperty(name = "amsha.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @Test
    void testPathSanitizer_replacesUnsafeCharacters() {
        String unsafe = "delegate:brahma-123*?\"<>|";
        String safe = PathSanitizer.sanitizeForFilesystem(unsafe);
        assertEquals("delegate_brahma-123______", safe);
    }

    @Test
    void testWorkspaceCleanup_withWindowsInvalidChars_doesNotThrow() throws IOException {
        String unsafeSessionId = "delegate:brahma-123";
        String safeDirName = PathSanitizer.sanitizeForFilesystem(unsafeSessionId);

        Path sessionDir = Path.of(workspaceRootDir, safeDirName);
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("test.txt"), "hello");

        assertTrue(Files.exists(sessionDir), "Directory should exist before cleanup");

        // Act & Assert: Should not throw InvalidPathException even on Windows
        assertDoesNotThrow(() -> workspaceManager.terminateSession(unsafeSessionId));

        // Verify workspace was actually wiped
        assertFalse(Files.exists(sessionDir), "Sanitized directory should have been wiped");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testPathSafety_windowsSpecific() {
        // Double check that Path.of works with the sanitized name but would fail with the original
        String unsafe = "illegal:char";
        assertThrows(
                Exception.class,
                () -> Path.of(workspaceRootDir, unsafe),
                "Windows should reject colons in path components");

        String safe = PathSanitizer.sanitizeForFilesystem(unsafe);
        assertDoesNotThrow(() -> Path.of(workspaceRootDir, safe), "Sanitized path should be accepted by Windows");
    }
}
