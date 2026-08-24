package com.amsha.deployment;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Named;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.amsha.channel.api.ChannelAllowlist;
import com.amsha.channel.api.IntentDetector;
import com.amsha.llm.api.StructuredOutputMode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Template;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.common.annotation.Identifier;

public class RestResourceBuildStepFailureTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(
                    () -> ShrinkWrap.create(JavaArchive.class).addClasses(TestProducers.class, FakeRestResource.class))
            .overrideConfigKey("amsha.vault.pepper", "dummy-pepper-for-test-purposes-123")
            .overrideConfigKey("amsha.data-dir", ":memory:")
            .overrideConfigKey("amsha.auto-seed-secrets", "false")
            .overrideConfigKey("amsha.llm.gemini.api-key", "dummy-gemini-key")
            .overrideConfigKey("quarkus.langchain4j.timeout", "10s")
            .overrideConfigKey("quarkus.arc.exclude-types", "com.amsha.core.**,com.amsha.persistence.sqlite.**")
            .setExpectedException(RuntimeException.class);

    @Test
    public void testBuildFails() {
        // This line should not be reached if setExpectedException works as intended
        Assertions.fail("Build should have failed due to missing @RunOnVirtualThread");
    }

    /**
     * Fake REST resource in com.amsha package WITHOUT @RunOnVirtualThread.
     * This triggers RestResourceBuildStep's validation.
     */
    @Path("/fake-test")
    @Produces(MediaType.APPLICATION_JSON)
    public static class FakeRestResource {
        @GET
        public String get() {
            return "ok";
        }
    }

    @Dependent
    public static class TestProducers {
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
        @Alternative
        @Priority(1)
        public ChatModel compactionModel() {
            return Mockito.mock(ChatModel.class);
        }

        @Produces
        @Identifier("compaction-mode")
        @Alternative
        @Priority(1)
        public StructuredOutputMode compactionMode() {
            return StructuredOutputMode.NATIVE;
        }

        @Produces
        @Identifier("intent-classifier-mode")
        @Alternative
        @Priority(1)
        public StructuredOutputMode intentClassifierMode() {
            return StructuredOutputMode.NATIVE;
        }

        @Produces
        @Identifier("agent-output-mode")
        @Alternative
        @Priority(1)
        public StructuredOutputMode agentOutputMode() {
            return StructuredOutputMode.NATIVE;
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
        public IntentDetector classifier() {
            return Mockito.mock(IntentDetector.class);
        }

        @Produces
        @ApplicationScoped
        public EmbeddingModel embeddingModel() {
            return Mockito.mock(EmbeddingModel.class);
        }
    }
}
