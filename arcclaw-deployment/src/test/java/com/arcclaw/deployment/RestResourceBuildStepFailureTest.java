package com.arcclaw.deployment;

import io.quarkus.test.QuarkusUnitTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class RestResourceBuildStepFailureTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
        .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
            .addClasses(InvalidResource.class, TestProducers.class))
        .overrideConfigKey("quarkus.datasource.db-kind", "sqlite")
        .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:sqlite::memory:")
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
}
