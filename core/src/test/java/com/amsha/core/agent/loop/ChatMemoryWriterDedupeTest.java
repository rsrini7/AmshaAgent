package com.amsha.core.agent.loop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;

import com.amsha.core.security.InputSanitizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatMemoryWriterDedupeTest {

    @Mock
    ChatMemoryStore chatMemoryStore;

    @Mock
    InputSanitizer piiScanReflex;

    @InjectMocks
    ChatMemoryWriter writer;

    @BeforeEach
    void setUp() {
        lenient().when(piiScanReflex.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void saveAnswerSkipsExactDuplicateLastAiMessage() {
        when(chatMemoryStore.getMessages("s1")).thenReturn(List.of(AiMessage.aiMessage("done")));

        writer.saveAnswer("s1", "done");

        verify(chatMemoryStore, never()).updateMessages(any(), any());
    }
}
