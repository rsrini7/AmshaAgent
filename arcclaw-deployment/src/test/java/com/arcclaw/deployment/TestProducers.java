package com.arcclaw.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;

import com.arcclaw.channel.api.ChannelAllowlist;
import com.arcclaw.channel.api.IntentClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Template;
import io.smallrye.common.annotation.Identifier;
import org.mockito.Mockito;

@Dependent
public class TestProducers {
    @Produces
    @ApplicationScoped
    public ObjectMapper mapper() {
        return new ObjectMapper();
    }

    @Produces
    @ApplicationScoped
    public MeterRegistry registry() {
        return new SimpleMeterRegistry();
    }

    @Produces
    @ApplicationScoped
    @Named("compaction")
    @Identifier("compaction")
    public ChatModel compactionModel() {
        return Mockito.mock(ChatModel.class);
    }

    @Produces
    public Template dummyTemplate() {
        return Mockito.mock(Template.class);
    }

    @Produces
    @ApplicationScoped
    public ChannelAllowlist allowlist() {
        return Mockito.mock(ChannelAllowlist.class);
    }

    @Produces
    @ApplicationScoped
    public IntentClassifier classifier() {
        return Mockito.mock(IntentClassifier.class);
    }

    @Produces
    @ApplicationScoped
    public EntityManager em() {
        return Mockito.mock(EntityManager.class);
    }
}
