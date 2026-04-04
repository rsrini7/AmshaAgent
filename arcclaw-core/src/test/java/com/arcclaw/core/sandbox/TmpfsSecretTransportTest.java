package com.arcclaw.core.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

public class TmpfsSecretTransportTest {

    @Test
    void testPrepareAndDeliverLifecyle(@TempDir Path tempDir) throws IOException {
        String sessionId = "testSession";
        
        // Setup raw secrets
        byte[] secret1 = "secret-value-1".getBytes();
        byte[] secret2 = "secret-value-2".getBytes();
        
        Map<String, byte[]> secrets = new LinkedHashMap<>();
        secrets.put("KEY1", secret1);
        secrets.put("KEY2", secret2);

        Path envFilePath = null;
        try (TmpfsSecretTransport transport = new TmpfsSecretTransport(sessionId, tempDir.toString(), "tmpfs")) {
            
            // 1. Prepare
            List<String> commandArgs = transport.prepare(secrets);
            
            assertNotNull(commandArgs);
            assertEquals(2, commandArgs.size());
            assertEquals("--env-file", commandArgs.get(0));
            
            envFilePath = Path.of(commandArgs.get(1));
            assertTrue(Files.exists(envFilePath));
            
            // Verify file sits inside the requested requested mount directory with the session prefix
            assertTrue(envFilePath.startsWith(tempDir));
            assertTrue(envFilePath.getFileName().toString().contains(sessionId));
            
            // Verify format is pure bytes without String instantiation weirdness
            String content = Files.readString(envFilePath);
            assertEquals("KEY1=secret-value-1\nKEY2=secret-value-2\n", content);
            
            // 2. Deliver (No op for tmpfs)
            assertDoesNotThrow(() -> transport.deliver(null));
        }

        // 3. Verify Cleanup was triggered automatically via try-with-resources AutoCloseable
        assertFalse(Files.exists(envFilePath), "Env file must be deleted");

        // 4. Verify original byte arrays were zeroed 
        // Note: The caller owned them, but the transport takes over ownership to wipe them
        for (byte b : secret1) {
            assertEquals((byte) 0, b, "Secret material 1 was not zeroed out");
        }
        for (byte b : secret2) {
            assertEquals((byte) 0, b, "Secret material 2 was not zeroed out");
        }
    }

    @Test
    void testEmptySecretsList(@TempDir Path tempDir) throws IOException {
        try (TmpfsSecretTransport transport = new TmpfsSecretTransport("sessionX", tempDir.toString(), "tmpfs")) {
            List<String> args = transport.prepare(Map.of());
            assertTrue(args.isEmpty(), "No secrets should yield no podman args");
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void testSweepStaleTmpFiles(@TempDir Path tempDir) throws IOException {
        Path staleSecret = tempDir.resolve("arcclaw-secrets-old.env");
        Path otherFile = tempDir.resolve("other-file.txt");
        
        Files.writeString(staleSecret, "stale content");
        Files.writeString(otherFile, "other content");
        
        assertTrue(Files.exists(staleSecret));
        assertTrue(Files.exists(otherFile));
        
        try (TmpfsSecretTransport transport = new TmpfsSecretTransport("test", tempDir.toString(), "tmpfs")) {
            transport.sweepStaleTmpFiles(tempDir);
        }
        
        assertFalse(Files.exists(staleSecret), "Stale secret should be swept");
        assertTrue(Files.exists(otherFile), "Other file should NOT be swept");
    }

    @Test
    void testSweepStaleTmpFilesNonExistentDir() {
        Path nonExistent = Path.of("/tmp/this-dir-should-not-exist-12345");
        try (TmpfsSecretTransport transport = new TmpfsSecretTransport("test", "/dev/shm", "tmpfs")) {
            assertDoesNotThrow(() -> transport.sweepStaleTmpFiles(nonExistent));
        }
    }

}
