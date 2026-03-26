package com.arcclaw.channel.telegram;

import com.arcclaw.channel.api.IntentClassifier;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Template;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import io.smallrye.common.annotation.Identifier;
import org.mockito.Mockito;

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
    @Priority(1)
    @ApplicationScoped
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public IntentClassifier intentClassifier() {
        return Mockito.mock(IntentClassifier.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    @Identifier("compaction")
    public ChatModel compactionModel() {
        return Mockito.mock(ChatModel.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public ChatModel defaultChatModel() {
        return Mockito.mock(ChatModel.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    @Named("architectTemplate")
    public Template architectTemplate() {
        return Mockito.mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    @Named("coderTemplate")
    public Template coderTemplate() {
        return Mockito.mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    @Named("coordinatorTemplate")
    public Template coordinatorTemplate() {
        return Mockito.mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    @Named("debuggerTemplate")
    public Template debuggerTemplate() {
        return Mockito.mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    @Named("researcherTemplate")
    public Template researcherTemplate() {
        return Mockito.mock(Template.class);
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    @Named("synthesizerTemplate")
    public Template synthesizerTemplate() {
        return Mockito.mock(Template.class);
    }
}
