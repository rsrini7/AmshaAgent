package com.arcclaw.channel.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import dev.langchain4j.model.chat.ChatModel;
import io.smallrye.common.annotation.Identifier;
import org.mockito.Mockito;

import com.arcclaw.core.agent.reasoning.AgentChatModelProvider;

/**
 * Mock producer and provider to satisfy LangChain4J ChatModel dependencies
 */
@ApplicationScoped
public class MockChatModelProducer implements AgentChatModelProvider {

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public ChatModel create() {
        return Mockito.mock(ChatModel.class);
    }

    @Produces
    @ApplicationScoped
    @Identifier("compaction")
    public ChatModel compactionModel() {
        return Mockito.mock(ChatModel.class);
    }
}
