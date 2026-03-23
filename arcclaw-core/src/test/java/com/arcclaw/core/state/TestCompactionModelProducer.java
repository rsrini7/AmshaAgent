package com.arcclaw.core.state;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import dev.langchain4j.model.chat.ChatModel;
import io.smallrye.common.annotation.Identifier;

@ApplicationScoped
public class TestCompactionModelProducer {

    @Produces
    @Identifier("compaction")
    @ApplicationScoped
    public ChatModel compactionModel() {
        ChatModel m = mock(ChatModel.class);
        when(m.chat(anyString())).thenReturn("test summary result");
        return m;
    }
}
