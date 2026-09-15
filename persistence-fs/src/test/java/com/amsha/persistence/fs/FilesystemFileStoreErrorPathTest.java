package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Error-path coverage for {@link FilesystemFileStore} — the store every other {@code Fs*} adapter delegates to.
 *
 * <p>Every catch block in the class is otherwise unexecuted: the happy-path tests never make the filesystem
 * refuse anything. The trick used throughout is to put <em>the wrong kind of thing</em> where the code expects
 * the right kind — a regular file where a directory is required (or vice versa). That reaches the
 * {@link IOException} arms deterministically, with no mocking and no reliance on {@code chmod}. The two
 * {@code deleteDirectory} arms do need real permission bits, because there is no way to make a child delete fail
 * with a well-formed tree; they restore the original mode in a {@code finally} block.
 *
 * <p>{@code FilesystemFileStore()} (the protected no-arg constructor) and {@code ensureDataDir()} are
 * package-private, so this test can drive them directly.
 */
@SuppressWarnings("java:S5443") // Test-only temp directory.
class FilesystemFileStoreErrorPathTest {

    private Path tempDir;
    private FilesystemFileStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-store-error-");
        store = new FilesystemFileStore(props(tempDir.toString()));
        store.ensureDataDir();
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(tempDir);
    }

    @Test
    void theNoArgConstructorFallsBackToTheDefaultDataDir() {
        FilesystemFileStore fallback = new FilesystemFileStore();

        Path resolved = fallback.resolve(List.of("a.json"));
        assertTrue(resolved.endsWith(Path.of("data", "fs", "a.json")), resolved.toString());
        assertNotNull(fallback.objectMapper(), "the fallback constructor still configures a mapper");
    }

    @Test
    void ensureDataDirFailsWhenAParentSegmentIsAFile() throws IOException {
        Path root = Files.createTempDirectory("amsha-fs-store-ensure-");
        try {
            Files.writeString(root.resolve("blocker"), "not a directory");
            FilesystemFileStore blocked =
                    new FilesystemFileStore(props(root.resolve("blocker/sub").toString()));

            UncheckedIOException ex = assertThrows(UncheckedIOException.class, blocked::ensureDataDir);

            assertTrue(ex.getMessage().startsWith("Cannot create data directory:"), ex.getMessage());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void writeFailsWhenAParentSegmentIsAFile() throws IOException {
        Files.writeString(store.resolve(List.of("blocker")), "not a directory");

        UncheckedIOException ex =
                assertThrows(UncheckedIOException.class, () -> store.write(List.of("blocker", "child.json"), "value"));

        assertTrue(ex.getMessage().startsWith("Failed to write:"), ex.getMessage());
    }

    @Test
    void readOfAMissingFileThrowsAnUncheckedIoException() {
        Path missing = store.resolve(List.of("missing.json"));

        UncheckedIOException ex =
                assertThrows(UncheckedIOException.class, () -> store.read(List.of("missing.json"), String.class));

        // Jackson opens the file with `new FileInputStream(...)`, which raises FileNotFoundException — a
        // sibling of NoSuchFileException, not a subtype. So the dedicated `catch (NoSuchFileException e)` arm
        // above this one is unreachable and this is the generic IOException arm that fires.
        assertTrue(ex.getMessage().startsWith("Failed to read:"), ex.getMessage());
        assertTrue(ex.getMessage().contains(missing.toString()), ex.getMessage());
    }

    @Test
    void readOfADirectoryThrowsAnUncheckedIoException() throws IOException {
        Files.createDirectory(store.resolve(List.of("a-directory")));

        UncheckedIOException ex =
                assertThrows(UncheckedIOException.class, () -> store.read(List.of("a-directory"), String.class));

        assertTrue(ex.getMessage().startsWith("Failed to read:"), ex.getMessage());
    }

    @Test
    void readOptionalFailsWhenTheTargetExistsButCannotBeParsed() throws IOException {
        // Files.exists() is true for a directory, so the guard is passed and the read itself has to fail.
        Files.createDirectory(store.resolve(List.of("a-directory")));

        UncheckedIOException ex = assertThrows(
                UncheckedIOException.class, () -> store.readOptional(List.of("a-directory"), String.class));

        assertTrue(ex.getMessage().startsWith("Failed to read:"), ex.getMessage());
    }

    @Test
    void deleteFailsForANonEmptyDirectory() throws IOException {
        Path dir = store.resolve(List.of("non-empty"));
        Files.createDirectory(dir);
        Files.writeString(dir.resolve("child.txt"), "x");

        UncheckedIOException ex = assertThrows(UncheckedIOException.class, () -> store.delete(List.of("non-empty")));

        assertTrue(ex.getMessage().startsWith("Failed to delete:"), ex.getMessage());
    }

    @Test
    void listFailsWhenTheDomainPathIsAFile() throws IOException {
        Files.writeString(store.resolve(List.of("a-file")), "x");

        UncheckedIOException ex =
                assertThrows(UncheckedIOException.class, () -> store.list(List.of("a-file"), ".json"));

        assertTrue(ex.getMessage().startsWith("Failed to list:"), ex.getMessage());
    }

    @Test
    void listDirsFailsWhenTheDomainPathIsAFile() throws IOException {
        Files.writeString(store.resolve(List.of("a-file")), "x");

        UncheckedIOException ex = assertThrows(UncheckedIOException.class, () -> store.listDirs(List.of("a-file")));

        assertTrue(ex.getMessage().startsWith("Failed to list dirs:"), ex.getMessage());
    }

    @Test
    void listAndListDirsReturnEmptyForAPathThatDoesNotExist() {
        assertTrue(store.list(List.of("absent"), ".json").isEmpty());
        assertTrue(store.listDirs(List.of("absent")).isEmpty());
    }

    @Test
    void deleteDirectoryFailsWhenAChildCannotBeRemoved() throws IOException {
        Path locked = store.resolve(List.of("locked"));
        Files.createDirectory(locked);
        Path inner = locked.resolve("inner");
        Files.createDirectory(inner);
        Files.writeString(inner.resolve("child.txt"), "x");

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(inner);
        Files.setPosixFilePermissions(inner, PosixFilePermissions.fromString("r-x------"));
        try {
            assertThrows(UncheckedIOException.class, () -> store.deleteDirectory(List.of("locked")));
        } finally {
            Files.setPosixFilePermissions(inner, original);
        }
    }

    @Test
    void deleteDirectoryFailsWhenTheDirectoryCannotBeWalked() throws IOException {
        Path unreadable = store.resolve(List.of("unreadable"));
        Files.createDirectory(unreadable);
        Files.writeString(unreadable.resolve("child.txt"), "x");

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(unreadable);
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        try {
            assertThrows(UncheckedIOException.class, () -> store.deleteDirectory(List.of("unreadable")));
        } finally {
            Files.setPosixFilePermissions(unreadable, original);
        }
    }

    @Test
    void deleteDirectoryIsANoOpForAPathThatDoesNotExist() {
        store.deleteDirectory(List.of("absent"));

        assertFalse(store.exists(List.of("absent")));
    }

    // ─── Helpers ───────────────────────────────────────────────

    private static FsStoreProperties props(String dataDir) {
        return new FsStoreProperties() {
            @Override
            public String dataDir() {
                return dataDir;
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

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort cleanup
                }
            });
        }
    }
}
