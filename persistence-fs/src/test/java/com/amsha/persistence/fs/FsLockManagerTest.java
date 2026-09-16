package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FsLockManager} against a real temp-directory data dir, so the advisory-lock
 * files and {@code .meta} files are exercised for real rather than mocked. The one arm that cannot
 * be reached in-process is {@code channel.tryLock() == null}, which needs a <em>second OS process</em>
 * to hold the file lock — an in-JVM competitor raises {@link java.nio.channels.OverlappingFileLockException}
 * instead (covered separately below).
 */
@SuppressWarnings({"java:S1192", "java:S5443"}) // Test-only: temp dirs are acceptable here
class FsLockManagerTest {

    private Path tempDir;
    private Path locksDir;
    private FsStoreProperties props;
    private FsLockManager manager;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("amsha-fs-locks-");
        props = propsFor(tempDir);
        locksDir = tempDir.resolve("locks");
        manager = new FsLockManager(props);
        manager.ensureLocksDir();
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    /* best-effort cleanup */
                }
            });
        }
    }

    // ─── Constructor ───────────────────────────────────────────

    @Test
    void theNoArgConstructorFallsBackToTheDefaultDataDir() {
        FsLockManager defaulted = new FsLockManager();
        assertNotNull(defaulted);
    }

    @Test
    void ensureLocksDirFailsWhenTheLocksPathIsAlreadyAFile() throws IOException {
        Path brokenRoot = tempDir.resolve("broken");
        Files.createDirectories(brokenRoot);
        Files.createFile(brokenRoot.resolve("locks"));
        FsLockManager broken = new FsLockManager(propsFor(brokenRoot));

        assertThrows(UncheckedIOException.class, broken::ensureLocksDir);
        // tryLock() swallows the same failure and reports "could not lock".
        assertFalse(broken.tryLock("any-key", 5));
    }

    // ─── tryLock / unlock ──────────────────────────────────────

    @Test
    void tryLockIsIdempotentWhileTheLockIsHeldAndUnlockReleasesIt() {
        assertTrue(manager.tryLock("alpha", 30));
        assertTrue(manager.tryLock("alpha", 30), "re-entrant while this instance holds it");
        assertTrue(manager.isLocked("alpha"));

        manager.unlock("alpha");
        assertFalse(manager.isLocked("alpha"));
        assertFalse(Files.exists(locksDir.resolve("alpha.lock")), "unlock deletes the .lock file");
        assertFalse(Files.exists(locksDir.resolve("alpha.meta")), "unlock deletes the .meta file");
    }

    @Test
    void aSecondManagerCannotTakeALockThisJvmAlreadyHolds() {
        assertTrue(manager.tryLock("contended", 30));
        try {
            FsLockManager second = new FsLockManager(props);
            second.ensureLocksDir();
            // A separate instance has an empty heldLocks map, so it reaches channel.tryLock(),
            // which raises OverlappingFileLockException for a lock held by this same JVM.
            assertFalse(second.tryLock("contended", 30));
        } finally {
            manager.unlock("contended");
        }
    }

    @Test
    void tryLockFailsWhenTheLockFilePathIsADirectory() throws IOException {
        Files.createDirectories(locksDir.resolve("blocked.lock"));
        assertFalse(manager.tryLock("blocked", 5));
    }

    @Test
    void tryLockFailsAndLeavesNothingHeldWhenTheMetaFileCannotBeWritten() throws IOException {
        // A directory where writeMeta() wants to move its temp file makes the move fail.
        Files.createDirectories(locksDir.resolve("metablocked.meta"));
        assertFalse(manager.tryLock("metablocked", 5));
        // isLocked() then reads a directory and gives up rather than throwing.
        assertFalse(manager.isLocked("metablocked"));
    }

    @Test
    void unlockIsANoOpForALockThisInstanceDoesNotHold() {
        assertDoesNotThrow(() -> manager.unlock("never-taken"));
    }

    // ─── renew ─────────────────────────────────────────────────

    @Test
    void renewFailsWhenNoLockIsHeld() {
        assertFalse(manager.renew("never-taken", 30));
    }

    @Test
    void renewExtendsAHeldLock() {
        assertTrue(manager.tryLock("renewable", 30));
        assertTrue(manager.renew("renewable", 600));
    }

    @Test
    void renewFailsWhenTheMetaFileCannotBeReplaced() throws IOException {
        assertTrue(manager.tryLock("renewblocked", 30));
        // Replace the meta file with a directory so the next writeMeta() move fails.
        Files.delete(locksDir.resolve("renewblocked.meta"));
        Files.createDirectories(locksDir.resolve("renewblocked.meta"));

        assertFalse(manager.renew("renewblocked", 600));
    }

    // ─── isLocked over on-disk state ───────────────────────────

    @Test
    void isLockedReadsAPersistedMetaFile() throws IOException {
        Files.writeString(
                locksDir.resolve("persisted.meta"),
                Instant.now().plusSeconds(3600).toString());
        assertTrue(manager.isLocked("persisted"));
    }

    @Test
    void isLockedPrunesAnExpiredMetaFile() throws IOException {
        Files.writeString(
                locksDir.resolve("expired.meta"),
                Instant.now().minus(1, ChronoUnit.HOURS).toString());
        assertFalse(manager.isLocked("expired"));
        assertFalse(Files.exists(locksDir.resolve("expired.meta")), "the stale meta file is removed");
    }

    @Test
    void isLockedReturnsFalseForAnEmptyMetaFile() throws IOException {
        Files.writeString(locksDir.resolve("empty.meta"), "   ");
        assertFalse(manager.isLocked("empty"));
    }

    @Test
    void isLockedReturnsFalseForAnUnparseableMetaFile() throws IOException {
        Files.writeString(locksDir.resolve("garbage.meta"), "not-an-instant");
        assertFalse(manager.isLocked("garbage"));
    }

    @Test
    void isLockedFallsBackToTheLockFileWhenThereIsNoMetaFile() throws IOException {
        // No .meta, but a .lock whose contents are a valid future instant.
        Files.writeString(
                locksDir.resolve("lockonly.lock"),
                Instant.now().plusSeconds(3600).toString());
        assertTrue(manager.isLocked("lockonly"));
    }

    // ─── releaseAll ────────────────────────────────────────────

    @Test
    void releaseAllReleasesEveryHeldLock() {
        assertTrue(manager.tryLock("first", 30));
        assertTrue(manager.tryLock("second", 30));

        manager.releaseAll();

        assertFalse(manager.isLocked("first"));
        assertFalse(manager.isLocked("second"));
    }

    // ─── Helpers ───────────────────────────────────────────────

    private static FsStoreProperties propsFor(Path dataDir) {
        return new FsStoreProperties() {
            @Override
            public String dataDir() {
                return dataDir.toString();
            }

            @Override
            public int lockTimeoutSeconds() {
                return 5;
            }

            @Override
            public String luceneIndexDir() {
                return "lucene-index";
            }

            @Override
            public int vectorDimension() {
                return 768;
            }
        };
    }
}
