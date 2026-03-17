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
            .addClasses(ValidResource.class, NonJpaResource.class));

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
        @Inject
        String someOtherBean;
    }
}
