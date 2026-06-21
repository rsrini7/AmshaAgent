package com.arcclaw.core.memory.tiered;

import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import com.arcclaw.persistence.api.RecallMemoryStorePort;
import com.arcclaw.persistence.api.RecallMessageDto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecallMemoryServiceTest {

    @Mock
    RecallMemoryStorePort recallStore;

    @InjectMocks
    RecallMemoryService service;

    private RecallMessageDto buildDto(String sessionId, int turn, String content) {
        return new RecallMessageDto(
                "msg-" + turn,
                sessionId,
                "user-1",
                turn,
                "hash-" + turn,
                "user",
                "USER_MESSAGE",
                content,
                null,
                null,
                false,
                false,
                Instant.now(),
                null);
    }

    @Test
    @DisplayName("insertOrIgnore delegates to RecallMemoryStorePort")
    void insertOrIgnoreDelegatesToStore() {
        RecallMessageDto dto = buildDto("s1", 0, "hello");

        service.insertOrIgnore(dto);

        verify(recallStore).insertOrIgnore(dto);
    }

    @Test
    @DisplayName("insertBatch delegates to RecallMemoryStorePort")
    void insertBatchDelegatesToStore() {
        List<RecallMessageDto> messages = List.of(buildDto("s1", 0, "hello"), buildDto("s1", 1, "world"));

        service.insertBatch(messages);

        verify(recallStore).insertBatch(messages);
    }

    @Test
    @DisplayName("insertBatch with empty list delegates without error")
    void insertBatchEmptyListDelegates() {
        service.insertBatch(List.of());

        verify(recallStore).insertBatch(List.of());
    }

    @Test
    @DisplayName("insertOrIgnore with null fields still delegates")
    void insertOrIgnoreWithMinimalDtoDelegates() {
        RecallMessageDto dto = new RecallMessageDto(
                null, "s1", "user-1", 0, "h", "user", "USER_MESSAGE", "msg", null, null, false, false, null, null);

        service.insertOrIgnore(dto);

        verify(recallStore).insertOrIgnore(dto);
    }
}
