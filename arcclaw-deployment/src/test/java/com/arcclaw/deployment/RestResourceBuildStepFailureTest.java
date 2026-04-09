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
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Template;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.common.annotation.Identifier;
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
            .addClasses(InvalidResource.class, TestProducers.class))
        .overrideConfigKey("quarkus.datasource.db-kind", "sqlite")
        .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:sqlite:target/arcclaw.db")
        .overrideConfigKey("quarkus.hibernate-orm.schema-management.strategy", "update")
        .overrideConfigKey("quarkus.hibernate-orm.dialect", "com.arcclaw.core.persistence.ArcClawSQLiteDialect")
        .overrideConfigKey("arcclaw.vault.pepper", "dummy-pepper-for-test-purposes-123")
        .overrideConfigKey("arcclaw.data-dir", "target/")
        .overrideConfigKey("arcclaw.auto-seed-secrets", "false")
        .overrideConfigKey("quarkus.langchain4j.timeout", "10s")
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
}
