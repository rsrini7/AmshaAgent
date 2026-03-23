package com.arcclaw.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Path;

import com.arcclaw.channel.api.ChannelAllowlist;
import com.arcclaw.channel.api.IntentClassifier;
import com.arcclaw.core.audit.AuditSchemaEnforcer;
import com.arcclaw.core.persistence.FtsConnectionInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.QuarkusUnitTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

public class RestResourceBuildStepFailureTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
        .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
            .addClasses(InvalidResource.class, TestProducers.class,
                FtsConnectionInterceptor.class,
                AuditSchemaEnforcer.class))
        .overrideConfigKey("quarkus.datasource.db-kind", "sqlite")
        .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:sqlite::memory:")
        .overrideConfigKey("quarkus.hibernate-orm.schema-management.strategy", "update")
        .overrideConfigKey("quarkus.hibernate-orm.dialect", "com.arcclaw.core.persistence.ArcClawSQLiteDialect")
        .overrideConfigKey("arcclaw.vault.pepper", "dummy-pepper-for-test-purposes-123")
        .overrideConfigKey("arcclaw.data-dir", ":memory:")
        .overrideConfigKey("arcclaw.auto-seed-secrets", "false")
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

    @Dependent
    public static class TestProducers {
        @Produces
        @ApplicationScoped
        public ObjectMapper mapper() {
            return new ObjectMapper();
        }

        @Produces
        @ApplicationScoped
        public io.micrometer.core.instrument.MeterRegistry registry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }

        @Produces
        @ApplicationScoped
        @Named("compaction")
        @io.smallrye.common.annotation.Identifier("compaction")
        public ChatModel compactionModel() {
            return Mockito.mock(ChatModel.class);
        }

        @Produces
        public io.quarkus.qute.Template dummyTemplate() {
            return Mockito.mock(io.quarkus.qute.Template.class);
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
    }
}
