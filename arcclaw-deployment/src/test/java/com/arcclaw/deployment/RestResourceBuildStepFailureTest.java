package com.arcclaw.deployment;

import io.quarkus.test.QuarkusUnitTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Assertions;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Path;

public class RestResourceBuildStepFailureTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
        .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
            .addClasses(InvalidResource.class, TestProducers.class))
        .overrideConfigKey("quarkus.datasource.db-kind", "sqlite")
        .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:sqlite::memory:")
        .setExpectedException(RuntimeException.class);

    @Test
    public void testBuildFails() {
        // This line should not be reached if setExpectedException works as intended
        Assertions.fail("Build should have failed due to missing @RunOnVirtualThread");
    }

    @Path("/invalid")
    public static class InvalidResource {
        @Inject
        EntityManager em;
    }

    @jakarta.enterprise.context.Dependent
    public static class TestProducers {
        @jakarta.enterprise.inject.Produces
        @jakarta.enterprise.context.ApplicationScoped
        public com.fasterxml.jackson.databind.ObjectMapper mapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        @jakarta.enterprise.inject.Produces
        @jakarta.enterprise.context.ApplicationScoped
        public io.micrometer.core.instrument.MeterRegistry registry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }

        @jakarta.enterprise.inject.Produces
        @jakarta.enterprise.context.ApplicationScoped
        @jakarta.inject.Named("compaction")
        @io.smallrye.common.annotation.Identifier("compaction")
        public dev.langchain4j.model.chat.ChatModel compactionModel() {
            return org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
        }

        @jakarta.enterprise.inject.Produces
        public io.quarkus.qute.Template dummyTemplate() {
            return org.mockito.Mockito.mock(io.quarkus.qute.Template.class);
        }

        @jakarta.enterprise.inject.Produces
        @jakarta.enterprise.context.ApplicationScoped
        public com.arcclaw.channel.api.ChannelAllowlist allowlist() {
            return org.mockito.Mockito.mock(com.arcclaw.channel.api.ChannelAllowlist.class);
        }

        @jakarta.enterprise.inject.Produces
        @jakarta.enterprise.context.ApplicationScoped
        public com.arcclaw.channel.api.IntentClassifier classifier() {
            return org.mockito.Mockito.mock(com.arcclaw.channel.api.IntentClassifier.class);
        }
    }
}
