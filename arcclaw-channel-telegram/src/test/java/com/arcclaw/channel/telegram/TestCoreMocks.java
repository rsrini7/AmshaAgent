package com.arcclaw.channel.telegram;

import org.mockito.Mockito;

import com.arcclaw.channel.api.IntentDetector;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Template;
import io.smallrye.common.annotation.Identifier;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

/**
 * Centralised mock producers for all @QuarkusTest tests in
 * arcclaw-channel-telegram.
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
        return Mockito.mock(IntentDetector.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Identifier("compaction")
    public ChatModel compactionModel() {
        return Mockito.mock(ChatModel.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    public ChatModel defaultChatModel() {
        return Mockito.mock(ChatModel.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("architectTemplate")
    public Template architectTemplate() {
        return Mockito.mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("coderTemplate")
    public Template coderTemplate() {
        return Mockito.mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("coordinatorTemplate")
    public Template coordinatorTemplate() {
        return Mockito.mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("debuggerTemplate")
    public Template debuggerTemplate() {
        return Mockito.mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("researcherTemplate")
    public Template researcherTemplate() {
        return Mockito.mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    @Named("synthesizerTemplate")
    public Template synthesizerTemplate() {
        return Mockito.mock(Template.class);
    }
    @Produces
    @Alternative
    @Priority(1000)
    @ApplicationScoped
    public EmbeddingModel embeddingModel() {
        return Mockito.mock(EmbeddingModel.class);
    }
}
