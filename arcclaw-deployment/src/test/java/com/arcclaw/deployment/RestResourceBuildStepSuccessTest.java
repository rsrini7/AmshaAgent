package com.arcclaw.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Path;

import com.arcclaw.channel.api.ChannelAllowlist;
import com.arcclaw.channel.api.IntentDetector;
import com.arcclaw.persistence.sqlite.AuditSchemaEnforcer;
import com.arcclaw.persistence.sqlite.FtsConnectionInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Template;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.common.annotation.Identifier;
import io.smallrye.common.annotation.RunOnVirtualThread;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

public class RestResourceBuildStepSuccessTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
        .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
            .addClasses(ValidResource.class, NonJpaResource.class, TestProducers.class,
                FtsConnectionInterceptor.class,
                AuditSchemaEnforcer.class))
        .overrideConfigKey("quarkus.datasource.db-kind", "sqlite")
        .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:sqlite::memory:")
        .overrideConfigKey("quarkus.hibernate-orm.schema-management.strategy", "update")
        .overrideConfigKey("quarkus.hibernate-orm.dialect", "com.arcclaw.persistence.sqlite.ArcClawSQLiteDialect")
        .overrideConfigKey("arcclaw.vault.pepper", "dummy-pepper-for-test-purposes-123")
        .overrideConfigKey("arcclaw.data-dir", ":memory:")
        .overrideConfigKey("arcclaw.auto-seed-secrets", "false")
        .overrideConfigKey("quarkus.langchain4j.timeout", "10s");

    @Test
    public void testBuildSucceeds() {
        // No-op, just verifying build success
    }

    @Path("/valid")
    @RunOnVirtualThread
    public static class ValidResource {
        @Inject
        EntityManager em;
    }

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
        public ChatModel compactionModel() {
            return mock(ChatModel.class);
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
