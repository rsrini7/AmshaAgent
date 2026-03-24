package com.arcclaw.app.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Vec0NativeLibraryInstallerTest {

    @Test
    void osArchKeyReturnsKnownValue() {
        String key = Vec0NativeLibraryInstaller.osArchKey();
        assertFalse("unknown".equals(key), "osArchKey should be known on standard CI/dev boxes");
    }

    @Test
    void nativeExtensionMatchesOs() {
        String ext = Vec0NativeLibraryInstaller.nativeExtension();
        assertTrue(ext.equals(".so") || ext.equals(".dll") || ext.equals(".dylib"), "ext must be one of .so, .dll, .dylib");
    }

    @Test
    void resourceExistsInJarForCurrentPlatform() throws IOException {
        String resourceName = "/native/vec0/vec0-" +
            Vec0NativeLibraryInstaller.osArchKey() +
            Vec0NativeLibraryInstaller.nativeExtension();
        try (InputStream in = Vec0NativeLibraryInstallerTest.class.getResourceAsStream(resourceName)) {
            assertNotNull(in, "Vec0 binary resource " + resourceName + " must exist in the JAR");
        }
    }

    @Test
    void installerDoesNotOverwriteExistingBinary(@TempDir Path tempDir) throws IOException {
        // Create a sentinel file at the target path
        Path sentinelDir = tempDir.resolve("lib");
        Files.createDirectories(sentinelDir);
        Path sentinel = sentinelDir.resolve("vec0" + Vec0NativeLibraryInstaller.nativeExtension());
        Files.writeString(sentinel, "sentinel");

        Vec0NativeLibraryInstaller installer = new Vec0NativeLibraryInstaller();
        installer.configuredVecPath = sentinelDir.resolve("vec0").toString();
        installer.onStart(null); // must not overwrite

        assertEquals("sentinel", Files.readString(sentinel));
    }
}
