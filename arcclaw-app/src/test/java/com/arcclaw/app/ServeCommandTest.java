package com.arcclaw.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Map;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.apache.camel.CamelContext;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ServeCommandTest.Profile.class)
class ServeCommandTest {

    // @AfterAll
    // static void dumpThreads() {
    //     Thread.getAllStackTraces().forEach((t, st) -> {
    //         System.err.println("THREAD: " + t.getName() + " daemon=" + t.isDaemon());
    //         for (StackTraceElement e : st) {
    //             System.err.println("  at " + e);
    //         }
    //         System.err.println();
    //     });
    // }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "arcclaw.channel.telegram.enabled", "true",
                "arcclaw.channel.telegram.bot-token", "mock-token-test",
                "quarkus.mcp.server.stdio.enabled", "false",
                "quarkus.opentelemetry.enabled", "false"
            );
        }
    }

    @Inject ServeCommand serveCommand;
    @Inject CamelContext camelContext;

    @Test
    void testServeCommandIsInjectable() {
        assertNotNull(serveCommand);
    }

    @Test
    void testServeCommandHasNoCamelInjection() throws Exception {
        // Verify ServeCommand no longer injects CamelContext or TelegramChannelRoute
        long camelFields = Arrays.stream(ServeCommand.class.getDeclaredFields())
            .filter(f -> f.getType().equals(CamelContext.class) ||
                         f.getType().getName().contains("TelegramChannelRoute"))
            .count();
        assertEquals(0, camelFields,
            "ServeCommand must not inject CamelContext or TelegramChannelRoute");
    }


    @Test
    void testTelegramRouteAbsentForMockToken() {
        // mock-token-test triggers placeholder guard in onStart() - route not registered
        assertNull(camelContext.getRoute("telegram-bot"),
            "telegram-bot route must be absent for placeholder token");
    }
}
