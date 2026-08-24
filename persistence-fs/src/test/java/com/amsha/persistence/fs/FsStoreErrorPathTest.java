package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CheckpointDto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FsStoreErrorPathTest {

    @TempDir
    Path tempDir;

    private FsCheckpointStoreAdapter checkpointStore;

    @BeforeEach
    void setUp() throws Exception {
        FsStoreProperties props = new FsStoreProperties() {
            @Override
            public String dataDir() {
                return tempDir.toString();
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
        FilesystemFileStore fileStore = new FilesystemFileStore(props);
        fileStore.ensureDataDir();

        checkpointStore = new FsCheckpointStoreAdapter();
        setField(checkpointStore, "store", fileStore);
        FsCheckpointHelper helper = new FsCheckpointHelper();
        setField(helper, "store", fileStore);
        helper.initIdSeq();
        setField(checkpointStore, "helper", helper);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException _) {
                }
            });
        }
    }

    @Test
    void testFindByIdNonExistent() {
        Optional<CheckpointDto> found = checkpointStore.findById(99999L);
        assertFalse(found.isPresent());
    }

    @Test
    void testFindBySessionAndRoleNonExistent() {
        Optional<CheckpointDto> found = checkpointStore.findBySessionAndRole("no-session", "no-role");
        assertFalse(found.isPresent());
    }

    @Test
    void testFindActiveBySessionEmpty() {
        List<CheckpointDto> active = checkpointStore.findActiveBySession("empty-session");
        assertTrue(active.isEmpty());
    }

    @Test
    void testFindByStatusEmpty() {
        List<CheckpointDto> found = checkpointStore.findByStatus("IN_PROGRESS");
        assertTrue(found.isEmpty());
    }

    @Test
    void testAtomicTransitionOnNonExistent() {
        assertFalse(checkpointStore.atomicTransition(99999L, "IN_PROGRESS", "COMPLETED"));
    }

    @Test
    void testLoadStepsForNonExistentCheckpoint() {
        assertTrue(checkpointStore.loadSteps(99999L).isEmpty());
    }

    @Test
    void testDeleteCheckpointNonExistentNoError() {
        checkpointStore.deleteCheckpoint("no-session", "no-role");
    }

    @Test
    void testHasPendingApprovalForNonExistentSession() {
        assertFalse(checkpointStore.hasPendingApproval("no-session"));
    }

    @Test
    void testFindLatestAwaitingApprovalNonExistent() {
        Optional<CheckpointDto> found = checkpointStore.findLatestAwaitingApproval("no-session");
        assertFalse(found.isPresent());
    }

    @Test
    void testMarkFailedOnNonExistentIsNoOp() {
        checkpointStore.markFailed(99999L, "reason", "ERR", "trace", "Ex");
    }

    @Test
    void testMarkCompletedOnNonExistentIsNoOp() {
        checkpointStore.markCompleted(99999L);
    }

    @Test
    void testTransitionStatusOnNonExistentIsNoOp() {
        checkpointStore.transitionStatus(99999L, "COMPLETED");
    }

    @Test
    void testFindLatestNonArchivedBySessionEmpty() {
        Optional<CheckpointDto> found = checkpointStore.findLatestNonArchivedBySession("no-session");
        assertFalse(found.isPresent());
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findField(clazz.getSuperclass(), fieldName);
            throw e;
        }
    }
}
