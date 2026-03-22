package com.arcclaw.deployment;

import io.quarkus.test.QuarkusUnitTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Path;
import io.smallrye.common.annotation.RunOnVirtualThread;

public class RestResourceBuildStepSuccessTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
        .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
            .addClasses(ValidResource.class, NonJpaResource.class, TestProducers.class,
                com.arcclaw.core.persistence.FtsConnectionInterceptor.class,
                com.arcclaw.core.audit.AuditSchemaEnforcer.class))
        .overrideConfigKey("quarkus.datasource.db-kind", "sqlite")
        .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:sqlite::memory:")
        .overrideConfigKey("quarkus.hibernate-orm.schema-management.strategy", "update")
        .overrideConfigKey("quarkus.hibernate-orm.dialect", "com.arcclaw.core.persistence.ArcClawSQLiteDialect")
        .overrideConfigKey("arcclaw.vault.pepper", "dummy-pepper-for-test-purposes-123")
        .overrideConfigKey("arcclaw.data-dir", ":memory:")
        .overrideConfigKey("arcclaw.auto-seed-secrets", "false");

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
        @jakarta.inject.Named("compaction") // Or @Identifier if using that
        @io.smallrye.common.annotation.Identifier("compaction")
        public dev.langchain4j.model.chat.ChatModel compactionModel() {
            return mock(dev.langchain4j.model.chat.ChatModel.class);
        }

        @jakarta.enterprise.inject.Produces
        public io.quarkus.qute.Template dummyTemplate() {
            return mock(io.quarkus.qute.Template.class);
        }
        
        @jakarta.enterprise.inject.Produces
        @jakarta.enterprise.context.ApplicationScoped
        public com.arcclaw.channel.api.ChannelAllowlist allowlist() {
            return mock(com.arcclaw.channel.api.ChannelAllowlist.class);
        }

        @jakarta.enterprise.inject.Produces
        @jakarta.enterprise.context.ApplicationScoped
        public com.arcclaw.channel.api.IntentClassifier classifier() {
            return mock(com.arcclaw.channel.api.IntentClassifier.class);
        }

        private <T> T mock(Class<T> clazz) {
            return org.mockito.Mockito.mock(clazz);
        }
    }
}
