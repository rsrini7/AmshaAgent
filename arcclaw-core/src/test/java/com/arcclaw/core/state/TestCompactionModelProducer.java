package com.arcclaw.core.state;

import dev.langchain4j.model.chat.ChatModel;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
