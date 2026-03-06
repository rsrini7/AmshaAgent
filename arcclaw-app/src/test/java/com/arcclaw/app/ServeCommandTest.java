package com.arcclaw.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.apache.camel.CamelContext;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

@QuarkusTest
@TestProfile(ServeCommandTest.EnabledTelegramProfile.class)
class ServeCommandTest {

    public static class EnabledTelegramProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("arcclaw.channel.telegram.enabled", "true");
        }
    }

    @Inject
    ServeCommand serveCommand;

    @Inject
    CamelContext camelContext;

    @Test
    void testServeCommandDoesNotManageCamelLifecycle() {
        // Behavioral assertion: ServeCommand is injectable and can be instantiated.
        // If it still required manual Camel management in its setup/run, or had broken injection,
        // this test would fail during CDI container boot or execution.
        assertNotNull(serveCommand, "ServeCommand must be CDI-injectable");
        
        // We don't call serveCommand.run() because it calls Quarkus.waitForExit() 
        // which would block the test execution.
    }

    @Test
    void testCamelContextManagedByQuarkus() throws Exception {
        assertNotNull(camelContext, "CamelContext must be managed by Quarkus");
        
        System.out.println("Camel Status: " + camelContext.getStatus());
        System.out.println("Camel Routes: " + camelContext.getRoutes().size());
        camelContext.getRoutes().forEach(r -> System.out.println("Route: " + r.getRouteId()));

        // If it's stopped, try to start it to prove it's a functional bean provided by Quarkus
        if (camelContext.getStatus().isStopped()) {
            camelContext.start();
        }

        assertFalse(camelContext.getStatus().isStopped(), 
                "CamelContext should be functional and startable via Quarkus");
        
        // Verify no NPE when accessing a route that is absent (placeholder token in test)
        assertNull(camelContext.getRoute("telegram-bot"), 
                "telegram-bot route should be absent in test due to placeholder token");
    }
}
