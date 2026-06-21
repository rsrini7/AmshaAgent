package com.amsha.app.mcp;

import static org.mockito.ArgumentMatchers.anyString;

import java.util.Map;

import com.amsha.app.security.ApiKeyRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

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
            return Map.of("amsha.mcp.server-enabled", "false");
        }
    }

    @Test
    void testEndpointsShouldBeDisabled() {
        RestAssured.given()
                .header("X-Amsha-API-Key", "test-key")
                .when()
                .get("/api/v1/mcp/tools")
                .then()
                .statusCode(404);

        RestAssured.given()
                .header("X-Amsha-API-Key", "test-key")
                .when()
                .get("/api/v1/mcp/status")
                .then()
                .statusCode(404);
    }
}
