package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.amsha.persistence.api.CheckpointDto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FsCheckpointStoreAdapterTest {

    @Mock
    FilesystemFileStore store;

    @Mock
    FsCheckpointHelper helper;

    @InjectMocks
    FsCheckpointStoreAdapter adapter;

    @Test
    void testSaveNewCheckpoint() {
        when(helper.nextId()).thenReturn(100L);
        CheckpointDto dto = mock(CheckpointDto.class);
        when(dto.id()).thenReturn(null);
        when(dto.sessionId()).thenReturn("sess-1");
        when(dto.agentRole()).thenReturn("DEFAULT");

        CheckpointDto saved = adapter.save(dto);
        assertEquals(100L, saved.id());
        verify(store).write(eq(List.of("checkpoints", "sess-1", "DEFAULT.json")), any(CheckpointDto.class));
    }

    @Test
    void testFindActiveBySession() {
        CheckpointDto active = mock(CheckpointDto.class);
        when(active.status()).thenReturn("IN_PROGRESS");
        CheckpointDto completed = mock(CheckpointDto.class);
        when(completed.status()).thenReturn("COMPLETED");

        when(helper.loadSession("sess-1")).thenReturn(List.of(active, completed));

        List<CheckpointDto> list = adapter.findActiveBySession("sess-1");
        assertEquals(1, list.size());
        assertEquals("IN_PROGRESS", list.get(0).status());
    }
}
