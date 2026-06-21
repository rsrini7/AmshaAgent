package com.amsha.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.amsha.persistence.api.MemoryChunkDto;
import com.amsha.persistence.api.MemoryRetrievalPort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TwoTierMemoryServiceTest {

    @Mock
    MemoryRetrievalPort memoryPort;

    @InjectMocks
    TwoTierMemoryService service;

    @Test
    void testDelegationToPort() {
        // Arrange
        MemoryChunkDto dto = new MemoryChunkDto("id-1", "text-1", 0.95);
        when(memoryPort.search(anyString(), anyInt(), anyString())).thenReturn(List.of(dto));

        // Act
        List<ScoredChunk> results = service.search("query-1", "user-1", 1);

        // Assert
        assertEquals(1, results.size());
        assertEquals("id-1", results.get(0).chunkId());
        assertEquals("text-1", results.get(0).text());
        assertEquals(0.95, results.get(0).score());

        verify(memoryPort).search("query-1", 1, "user-1");
    }
}
