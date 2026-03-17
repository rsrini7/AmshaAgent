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
            .addClasses(InvalidResource.class))
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
}
