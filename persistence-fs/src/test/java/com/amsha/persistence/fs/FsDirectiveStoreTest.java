package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FsDirectiveStoreTest {

    @Mock
    FilesystemFileStore fileStore;

    FsDirectiveStore store;

    @BeforeEach
    void setUp() {
        store = new FsDirectiveStore();
        store.store = fileStore;
    }

    @Test
    void testInsertAndGetInjectable() {
        SynthesizedDirective d = new SynthesizedDirective(
                "dir-1",
                "user-1",
                "text",
                DirectiveState.ACTIVE,
                0.9,
                "[]",
                "hash-123",
                1,
                0,
                Instant.now(),
                Instant.now());

        when(fileStore.readOptional(any(), eq(FsDirectiveStore.DirectiveBatch.class)))
                .thenReturn(Optional.empty());

        int count = store.insert(d);
        assertEquals(1, count);
        verify(fileStore).write(any(), any());
    }

    @Test
    void testGetByStateAndDelete() {
        SynthesizedDirective d = new SynthesizedDirective(
                "dir-2",
                "user-1",
                "text 2",
                DirectiveState.ACTIVE,
                0.8,
                "[]",
                "hash-456",
                1,
                0,
                Instant.now(),
                Instant.now());

        when(fileStore.list(List.of("directives"), ".json")).thenReturn(List.of("user-1.json"));
        when(fileStore.readOptional(List.of("directives", "user-1.json"), FsDirectiveStore.DirectiveBatch.class))
                .thenReturn(Optional.of(new FsDirectiveStore.DirectiveBatch(List.of(d))));

        List<SynthesizedDirective> active = store.getByState(DirectiveState.ACTIVE, 10);
        assertEquals(1, active.size());

        SynthesizedDirective byId = store.getById("dir-2");
        assertNotNull(byId);
        assertEquals("dir-2", byId.id());

        boolean deleted = store.delete("dir-2");
        assertTrue(deleted);
    }
}
