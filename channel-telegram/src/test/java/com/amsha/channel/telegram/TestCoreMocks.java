package com.amsha.channel.telegram;

import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

import com.amsha.channel.api.IntentDetector;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Template;
import io.smallrye.common.annotation.Identifier;

/**
 * Centralised mock producers for all @QuarkusTest tests in
 * amsha-channel-telegram.
 * Uses @Alternative @Priority(1) to cleanly override platform/core beans
 * without causing ambiguity (per TROUBLESHOOTING.md §10 decision matrix).
 */
@ApplicationScoped
public class TestCoreMocks {

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    public IntentDetector IntentDetector() {
        return mock(IntentDetector.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Identifier("compaction")
    public ChatModel compactionModel() {
        return mock(ChatModel.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    public ChatModel defaultChatModel() {
        return mock(ChatModel.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("architectTemplate")
    public Template architectTemplate() {
        return mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("coderTemplate")
    public Template coderTemplate() {
        return mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("coordinatorTemplate")
    public Template coordinatorTemplate() {
        return mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("debuggerTemplate")
    public Template debuggerTemplate() {
        return mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("researcherTemplate")
    public Template researcherTemplate() {
        return mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("synthesizerTemplate")
    public Template synthesizerTemplate() {
        return mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    public EmbeddingModel embeddingModel() {
        return mock(EmbeddingModel.class);
    }
}
