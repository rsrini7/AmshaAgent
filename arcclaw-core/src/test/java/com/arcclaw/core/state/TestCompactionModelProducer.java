package com.arcclaw.core.state;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.Mock;
import io.smallrye.common.annotation.Identifier;

@ApplicationScoped
@Mock
public class TestCompactionModelProducer {

    @Produces
    @Identifier("compaction")
    @ApplicationScoped
    public ChatModel compactionModel() {
        ChatModel m = mock(ChatModel.class);
        when(m.chat(anyString())).thenReturn("test summary result");
        return m;
    }

    @Produces
    @Identifier("compaction-mode")
    public String compactionMode() {
        return "NATIVE";
    }

    @Produces
    @Identifier("intent-classifier-mode")
    public String intentClassifierMode() {
        return "NATIVE";
    }

    @Produces
    @Identifier("agent-output-mode")
    public String agentOutputMode() {
        return "NATIVE";
    }
}
