package com.amsha.persistence.fs;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.AdvisoryLockPort;

import org.jboss.logging.Logger;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsLockManager implements AdvisoryLockPort {

    private static final Logger log = Logger.getLogger(FsLockManager.class);

    private final Path locksDir;
    private final Map<String, LockHolder> heldLocks = new ConcurrentHashMap<>();

    @Inject
    public FsLockManager(FsStoreProperties properties) {
        this.locksDir =
                Path.of(properties.dataDir()).resolve("locks").toAbsolutePath().normalize();
    }

    protected FsLockManager() {
        this.locksDir = Path.of("./data/fs/locks").toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureLocksDir() {
        try {
            Files.createDirectories(locksDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create locks directory: " + locksDir, e);
        }
    }

    @PreDestroy
    void releaseAll() {
        heldLocks.keySet().forEach(this::unlock);
    }

    @Override
    public boolean tryLock(String lockKey, int timeoutSeconds) {
        if (heldLocks.containsKey(lockKey)) return true;

        Path lockFile = lockFilePath(lockKey);
        try {
            Files.createDirectories(lockFile.getParent());
        } catch (IOException _) {
            return false;
        }
        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(lockFile.toFile(), "rw");
        } catch (IOException _) {
            return false;
        }
        FileChannel channel = raf.getChannel();
        try {
            FileLock fileLock = channel.tryLock();
            if (fileLock == null) {
                closeQuietly(raf);
                return false;
            }
            raf.writeBytes(Instant.now().plusSeconds(timeoutSeconds).toString());
            raf.getFD().sync();
            // raf intentionally NOT closed — held by LockHolder until unlock()
            heldLocks.put(lockKey, new LockHolder(raf, channel, fileLock));
            return true;
        } catch (OverlappingFileLockException _) {
            closeQuietly(raf);
            return false;
        } catch (IOException _) {
            closeQuietly(raf);
            return false;
        }
    }

    private static void closeQuietly(RandomAccessFile raf) {
        try {
            raf.close();
        } catch (IOException _) {
            // best-effort cleanup
        }
    }

    @Override
    public void unlock(String lockKey) {
        LockHolder holder = heldLocks.remove(lockKey);
        if (holder != null) {
            try {
                holder.fileLock.release();
                holder.channel.close();
                holder.raf.close();
                Files.deleteIfExists(lockFilePath(lockKey));
            } catch (IOException e) {
                log.debugf("Lock release failed (best effort): %s", LogUtil.rootMessage(e));
            }
        }
    }

    @Override
    public boolean isLocked(String lockKey) {
        if (heldLocks.containsKey(lockKey)) return true;
        Path lockFile = lockFilePath(lockKey);
        if (!Files.exists(lockFile)) return false;
        try {
            String content = Files.readString(lockFile);
            Instant expiresAt = Instant.parse(content);
            if (Instant.now().isAfter(expiresAt)) {
                Files.deleteIfExists(lockFile);
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Path lockFilePath(String lockKey) {
        String safeName = lockKey.replaceAll("[^a-zA-Z0-9._-]", "_");
        return locksDir.resolve(safeName + ".lock");
    }

    private record LockHolder(RandomAccessFile raf, FileChannel channel, FileLock fileLock) {}
}
