package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;

import com.arcclaw.core.state.CompactionEventBridge;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CompactionSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompactionSummaryCacheTest {

    @Mock
    CompactionStorePort summaryRepository;

    @Mock
    CompactionEventBridge compactionEventBridge;

    CompactionSummaryCache cache;

    @BeforeEach
    void setUp() {
        cache = new CompactionSummaryCache();
        cache.summaryRepository = summaryRepository;
        cache.compactionEventBridge = compactionEventBridge;
    }

    @Test
    void getSummary_WhenNotLoaded_FetchesFromRepository() {
        when(summaryRepository.findLatestBySessionId("sess-1"))
                .thenReturn(Optional.of(new CompactionSummaryDto("sess-1", "summary text", Instant.now(), 1, "user-1")));

        Optional<String> result = cache.getSummary("sess-1");

        assertTrue(result.isPresent());
        assertEquals("summary text", result.get());
        verify(compactionEventBridge).awaitIfInFlight("sess-1");
        verify(summaryRepository).findLatestBySessionId("sess-1");
    }

    @Test
    void getSummary_WhenRepositoryReturnsEmpty_ReturnsEmpty() {
        when(summaryRepository.findLatestBySessionId("sess-1")).thenReturn(Optional.empty());

        Optional<String> result = cache.getSummary("sess-1");

        assertFalse(result.isPresent());
    }

    @Test
    void getSummary_WhenAlreadyLoaded_ReturnsCachedValue() {
        when(summaryRepository.findLatestBySessionId("sess-1"))
                .thenReturn(Optional.of(new CompactionSummaryDto("sess-1", "cached text", Instant.now(), 1, "user-1")));

        cache.getSummary("sess-1");
        Optional<String> result = cache.getSummary("sess-1");

        assertTrue(result.isPresent());
        assertEquals("cached text", result.get());
        verify(summaryRepository, times(1)).findLatestBySessionId("sess-1");
    }

    @Test
    void getSummary_WhenAlreadyLoaded_ReturnsSameValueForAnySession() {
        when(summaryRepository.findLatestBySessionId("sess-1"))
                .thenReturn(Optional.of(new CompactionSummaryDto("sess-1", "cached", Instant.now(), 1, "user-1")));

        assertEquals("cached", cache.getSummary("sess-1").get());
        // Cache is session-agnostic; once loaded, all sessions return the same value
        assertEquals("cached", cache.getSummary("sess-2").get());

        verify(summaryRepository, times(1)).findLatestBySessionId("sess-1");
        verify(summaryRepository, never()).findLatestBySessionId("sess-2");
    }

    @Test
    void invalidate_WhenLoaded_ResetsCache() {
        when(summaryRepository.findLatestBySessionId("sess-1"))
                .thenReturn(Optional.of(new CompactionSummaryDto("sess-1", "text", Instant.now(), 1, "user-1")));

        cache.getSummary("sess-1");
        cache.invalidate();
        cache.getSummary("sess-1");

        verify(summaryRepository, times(2)).findLatestBySessionId("sess-1");
    }

    @Test
    void invalidate_WhenNotLoaded_DoesNothing() {
        cache.invalidate();
        verifyNoInteractions(summaryRepository);
    }
}
