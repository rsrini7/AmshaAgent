package com.amsha.persistence.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serializes checkpoint envelope mutations within and across processes. */
final class FsCheckpointLock {

    private static final String LOCK_FILE = ".checkpoint.lock";
    private static final ReentrantLock JVM_LOCK = new ReentrantLock(true);
    private static final ThreadLocal<Set<Path>> HELD_PATHS = ThreadLocal.withInitial(HashSet::new);

    private FsCheckpointLock() {}

    static <T> T withLock(FilesystemFileStore store, Supplier<T> action) {
        Path lockPath = store.resolve(List.of(FsCheckpointHelper.DOMAIN, LOCK_FILE))
                .toAbsolutePath()
                .normalize();
        JVM_LOCK.lock();
        Set<Path> heldPaths = HELD_PATHS.get();
        try {
            if (heldPaths.contains(lockPath)) {
                return action.get();
            }
            Files.createDirectories(lockPath.getParent());
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock fileLock = channel.lock()) {
                heldPaths.add(lockPath);
                try {
                    return action.get();
                } finally {
                    heldPaths.remove(lockPath);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to acquire checkpoint store lock", e);
        } finally {
            if (heldPaths.isEmpty()) {
                HELD_PATHS.remove();
            }
            JVM_LOCK.unlock();
        }
    }
}
