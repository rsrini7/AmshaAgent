package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FsSchemaContractManagerTest {

    @Test
    void emptyRootInitializesMarkerAtomically(@TempDir Path tempDir) throws IOException {
        FsSchemaContractManager manager = new FsSchemaContractManager(tempDir);
        manager.verifyOrInitialize();

        Path manifestPath = tempDir.resolve(FsSchemaContractManager.MANIFEST_FILE_NAME);
        assertTrue(Files.exists(manifestPath), "schema-contract.json must be created");

        String content = Files.readString(manifestPath);
        assertTrue(content.contains(FsSchemaContractManager.CONTRACT_NAME));
        assertTrue(content.contains("\"contract_version\" : 1"));
    }

    @Test
    void emptyLocksDirectoryTreatedAsEmptyRoot(@TempDir Path tempDir) {
        Path locksDir = tempDir.resolve("locks");
        assertDoesNotThrow(() -> Files.createDirectories(locksDir));

        FsSchemaContractManager manager = new FsSchemaContractManager(tempDir);
        assertDoesNotThrow(manager::verifyOrInitialize);

        Path manifestPath = tempDir.resolve(FsSchemaContractManager.MANIFEST_FILE_NAME);
        assertTrue(Files.exists(manifestPath));
    }

    @Test
    void existingValidMarkerPasses(@TempDir Path tempDir) throws IOException {
        Path manifestPath = tempDir.resolve(FsSchemaContractManager.MANIFEST_FILE_NAME);
        Files.writeString(manifestPath, """
                {
                  "contract_name": "tool-attempt-settlement",
                  "contract_version": 1,
                  "applied_at": "2026-11-02T00:00:00Z"
                }
                """);

        FsSchemaContractManager manager = new FsSchemaContractManager(tempDir);
        assertDoesNotThrow(manager::verifyOrInitialize);
    }

    @Test
    void camelCasePropertyNamesAccepted(@TempDir Path tempDir) throws IOException {
        Path manifestPath = tempDir.resolve(FsSchemaContractManager.MANIFEST_FILE_NAME);
        Files.writeString(manifestPath, """
                {
                  "contractName": "tool-attempt-settlement",
                  "contractVersion": 1,
                  "appliedAt": "2026-11-02T00:00:00Z"
                }
                """);

        FsSchemaContractManager manager = new FsSchemaContractManager(tempDir);
        assertDoesNotThrow(manager::verifyOrInitialize);
    }

    @Test
    void nonEmptyRootMissingMarkerFailsClosed(@TempDir Path tempDir) throws IOException {
        Path someDataDir = tempDir.resolve("checkpoints");
        Files.createDirectories(someDataDir);
        Files.writeString(someDataDir.resolve("checkpoint-1.json"), "{}");

        FsSchemaContractManager manager = new FsSchemaContractManager(tempDir);
        IllegalStateException ex = assertThrows(IllegalStateException.class, manager::verifyOrInitialize);
        assertTrue(ex.getMessage().contains("missing schema-contract.json"));
    }

    @Test
    void malformedMarkerFailsClosed(@TempDir Path tempDir) throws IOException {
        Path manifestPath = tempDir.resolve(FsSchemaContractManager.MANIFEST_FILE_NAME);
        Files.writeString(manifestPath, "{not-json");

        FsSchemaContractManager manager = new FsSchemaContractManager(tempDir);
        IllegalStateException ex = assertThrows(IllegalStateException.class, manager::verifyOrInitialize);
        assertTrue(ex.getMessage().contains("malformed"));
    }

    @Test
    void unsupportedContractVersionFailsClosed(@TempDir Path tempDir) throws IOException {
        Path manifestPath = tempDir.resolve(FsSchemaContractManager.MANIFEST_FILE_NAME);
        Files.writeString(manifestPath, """
                {
                  "contract_name": "tool-attempt-settlement",
                  "contract_version": 2,
                  "applied_at": "2026-11-02T00:00:00Z"
                }
                """);

        FsSchemaContractManager manager = new FsSchemaContractManager(tempDir);
        IllegalStateException ex = assertThrows(IllegalStateException.class, manager::verifyOrInitialize);
        assertTrue(ex.getMessage().contains("version mismatch"));
    }

    @Test
    void unsupportedContractNameFailsClosed(@TempDir Path tempDir) throws IOException {
        Path manifestPath = tempDir.resolve(FsSchemaContractManager.MANIFEST_FILE_NAME);
        Files.writeString(manifestPath, """
                {
                  "contract_name": "other-unknown-contract",
                  "contract_version": 1,
                  "applied_at": "2026-11-02T00:00:00Z"
                }
                """);

        FsSchemaContractManager manager = new FsSchemaContractManager(tempDir);
        IllegalStateException ex = assertThrows(IllegalStateException.class, manager::verifyOrInitialize);
        assertTrue(ex.getMessage().contains("expected contract 'tool-attempt-settlement'"));
    }

    @Test
    void dataDirAccessorReturnsConfiguredPath(@TempDir Path tempDir) {
        FsSchemaContractManager manager = new FsSchemaContractManager(tempDir);
        assertEquals(tempDir, manager.dataDir());
    }

    @Test
    void interruptedManifestCreationCleansStaleTempFileAndInitializesFreshRoot(@TempDir Path tempDir)
            throws IOException {
        Path staleTmp = tempDir.resolve("schema-contract-interrupted.tmp");
        Files.writeString(staleTmp, "partial-content");

        FsSchemaContractManager manager = new FsSchemaContractManager(tempDir);
        assertDoesNotThrow(manager::verifyOrInitialize);

        assertFalse(Files.exists(staleTmp), "stale temp file must be cleaned up");
        Path manifestPath = tempDir.resolve(FsSchemaContractManager.MANIFEST_FILE_NAME);
        assertTrue(Files.exists(manifestPath), "schema-contract.json must be created");
    }

    @Test
    void interruptedTempFileWithExistingDataStillFailsClosed(@TempDir Path tempDir) throws IOException {
        Path staleTmp = tempDir.resolve("schema-contract-interrupted.tmp");
        Files.writeString(staleTmp, "partial-content");

        Path sessionsDir = tempDir.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Files.writeString(sessionsDir.resolve("session-1.json"), "{}");

        FsSchemaContractManager manager = new FsSchemaContractManager(tempDir);
        IllegalStateException ex = assertThrows(IllegalStateException.class, manager::verifyOrInitialize);
        assertTrue(ex.getMessage().contains("missing schema-contract.json"));
    }
}
