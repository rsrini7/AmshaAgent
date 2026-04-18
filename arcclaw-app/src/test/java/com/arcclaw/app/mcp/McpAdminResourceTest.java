package com.arcclaw.app.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.Map;
import java.util.Optional;

import com.arcclaw.app.security.ApiKeyRegistry;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
@TestProfile(McpAdminResourceTest.EnabledMcpProfile.class)
class McpAdminResourceTest {

    public static class EnabledMcpProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("arcclaw.mcp.server-enabled", "true");
        }
    }

    @InjectMock
    ApiKeyRegistry apiKeyRegistry;

    @InjectMock
    ToolRegistry toolRegistry;

    @BeforeEach
    void setup() {
        Mockito.when(apiKeyRegistry.isValidKey(anyString())).thenReturn(true);
        Mockito.when(apiKeyRegistry.isAllowed(anyString())).thenReturn(true);
        
        AgentTool mockTool = Mockito.mock(AgentTool.class);
        JsonObjectSchema schema = JsonObjectSchema.builder().build();
        Mockito.when(mockTool.parametersSchema()).thenReturn(schema);
        Mockito.when(mockTool.name()).thenReturn("mock-tool");
        Mockito.when(toolRegistry.findTool(anyString())).thenReturn(Optional.of(mockTool));
    }

    @Test
    void testListToolsEndpoint() {
        given()
          .header("X-ArcClaw-API-Key", "test-key")
          .when().get("/api/v1/mcp/tools")
          .then()
             .statusCode(200)
             .body("$", hasKey("exposedInternalTools"))
             .body("$", hasKey("registeredDynamicTools"));
    }

    @Test
    void testStatusEndpoint() {
        given()
          .header("X-ArcClaw-API-Key", "test-key")
          .when().get("/api/v1/mcp/status")
          .then()
             .statusCode(200)
             .body("serverEnabled", is(true));
    }
}
