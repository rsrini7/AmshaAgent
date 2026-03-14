package com.arcclaw.app.mcp;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import com.arcclaw.app.security.ApiKeyRegistry;
import static org.mockito.ArgumentMatchers.anyString;

@QuarkusTest
@TestProfile(McpAdminDisabledTest.DisabledMcpProfile.class)
class McpAdminDisabledTest {

    @InjectMock
    ApiKeyRegistry apiKeyRegistry;

    @BeforeEach
    void setup() {
        Mockito.when(apiKeyRegistry.isValidKey(anyString())).thenReturn(true);
        Mockito.when(apiKeyRegistry.isAllowed(anyString())).thenReturn(true);
    }

    public static class DisabledMcpProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("arcclaw.mcp.server-enabled", "false");
        }
    }

    @Test
    void testEndpointsShouldBeDisabled() {
        io.restassured.RestAssured.given()
            .header("X-ArcClaw-API-Key", "test-key")
            .when().get("/api/v1/mcp/tools")
            .then()
            .statusCode(404);

        io.restassured.RestAssured.given()
            .header("X-ArcClaw-API-Key", "test-key")
            .when().get("/api/v1/mcp/status")
            .then()
            .statusCode(404);
    }
}
