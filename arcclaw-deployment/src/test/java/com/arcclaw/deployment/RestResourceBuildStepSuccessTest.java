package com.arcclaw.deployment;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.ws.rs.Path;

import com.arcclaw.channel.api.ChannelAllowlist;
import com.arcclaw.channel.api.IntentDetector;
import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Template;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.common.annotation.Identifier;

public class RestResourceBuildStepSuccessTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClasses(TestProducers.class))
            .overrideConfigKey("arcclaw.vault.pepper", "dummy-pepper-for-test-purposes-123")
            .overrideConfigKey("arcclaw.data-dir", ":memory:")
            .overrideConfigKey("arcclaw.auto-seed-secrets", "false")
            .overrideConfigKey("arcclaw.llm.gemini.api-key", "dummy-gemini-key")
            .overrideConfigKey("quarkus.langchain4j.timeout", "10s")
            .overrideConfigKey("quarkus.arc.exclude-types", "com.arcclaw.core.**,com.arcclaw.persistence.sqlite.**");

    @Path("/non-jpa")
    public static class NonJpaResource {
        // No JPA fields, should be ignored by build step
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
        @Named("compaction") // Or @Identifier if using that
        @Identifier("compaction")
        @Alternative
        @Priority(1)
        public ChatModel compactionModel() {
            return mock(ChatModel.class);
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
            return mock(Template.class);
        }

        @Produces
        @ApplicationScoped
        public ChannelAllowlist allowlist() {
            return mock(ChannelAllowlist.class);
        }

        @Produces
        @ApplicationScoped
        public IntentDetector classifier() {
            return mock(IntentDetector.class);
        }

        @Produces
        @ApplicationScoped
        public EmbeddingModel embeddingModel() {
            return mock(EmbeddingModel.class);
        }

        private <T> T mock(Class<T> clazz) {
            return Mockito.mock(clazz);
        }
    }
}
