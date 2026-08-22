package com.amsha.app.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;
import java.util.Optional;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.persistence.AsyncPersistenceEngine;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;

@QuarkusTest
@TestProfile(McpServerE2ETest.McpE2EProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpServerE2ETest {

    public static class McpE2EProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("amsha.mcp.server-enabled", "true");
        }
    }

    private static String mcpSessionId;

    @InjectMock
    ApiKeyRegistry apiKeyRegistry;

    @InjectMock
    AsyncPersistenceEngine persistenceEngine;

    @InjectMock
    ToolRegistry toolRegistry;

    @BeforeEach
    void setup() {
        when(apiKeyRegistry.isValidKey(anyString())).thenReturn(true);
        when(apiKeyRegistry.isAllowed(anyString())).thenReturn(true);
        doNothing().when(persistenceEngine).submitCritical(any());
        when(toolRegistry.invokeTool(anyString(), anyString(), anyString()))
                .thenReturn(ToolResponse.success("mock-tool-result"));

        AgentTool mockTool = mock(AgentTool.class);
        JsonObjectSchema schema = JsonObjectSchema.builder().build();
        when(mockTool.parametersSchema()).thenReturn(schema);
        when(mockTool.name()).thenReturn("mock-tool");
        when(toolRegistry.findTool(anyString())).thenReturn(Optional.of(mockTool));
    }

    @Test
    @Order(1)
    void testMcpInitialize() {
        String body = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "initialize",
              "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "test-client", "version": "1.0"}
              }
            }
            """;

        Response resp = RestAssured.given()
                .log()
                .all()
                .header("X-Amsha-API-Key", "test-key")
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body(body)
                .when()
                .post("/mcp")
                .then()
                .log()
                .all()
                .statusCode(200)
                .extract()
                .response();

        mcpSessionId = resp.header("Mcp-Session-Id");
        assertNotNull(mcpSessionId, "Server must return Mcp-Session-Id header on initialize");

        String jsonResp = resp.body().asString();
        String json = jsonResp.startsWith("data:") ? jsonResp.substring(5).trim() : jsonResp;
        assertTrue(json.contains("\"protocolVersion\""), "Initialize result must contain protocolVersion");
    }

    @Test
    @Order(2)
    void testMcpToolsListContainsConfiguredTools() {
        assertNotNull(mcpSessionId, "Depends on testMcpInitialize running first");

        RestAssured.given()
                .header("X-Amsha-API-Key", "test-key")
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header("Mcp-Session-Id", mcpSessionId)
                .body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}")
                .when()
                .post("/mcp")
                .then()
                .statusCode(202);

        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";

        String raw = RestAssured.given()
                .header("X-Amsha-API-Key", "test-key")
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header("Mcp-Session-Id", mcpSessionId)
                .body(body)
                .when()
                .post("/mcp")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString();

        String json = raw.startsWith("data:") ? raw.substring(5).trim() : raw;
        assertTrue(
                json.contains("\"" + ToolCapability.GITHUB.toolName() + "\""),
                "tools/list must contain '" + ToolCapability.GITHUB.toolName() + "'. Got: " + json);
        assertTrue(
                json.contains("\"" + ToolCapability.RECALL_MEMORY_SEARCH.toolName() + "\""),
                "tools/list must contain '" + ToolCapability.RECALL_MEMORY_SEARCH.toolName() + "'. Got: " + json);
    }

    @Test
    @Order(3)
    void testMcpToolsCallRecallMemorySearch() {
        assertNotNull(mcpSessionId, "Depends on testMcpInitialize running first");

        String body = String.format("""
            {
              "jsonrpc": "2.0",
              "id": 3,
              "method": "tools/call",
              "params": {
                "name": "%s",
                "arguments": {"query": "test query"}
              }
            }
            """, ToolCapability.RECALL_MEMORY_SEARCH.toolName());

        String raw = RestAssured.given()
                .header("X-Amsha-API-Key", "test-key")
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header("Mcp-Session-Id", mcpSessionId)
                .body(body)
                .when()
                .post("/mcp")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString();

        String json = raw.startsWith("data:") ? raw.substring(5).trim() : raw;
        assertTrue(
                json.contains("\"content\"") || json.contains("mock-tool-result"),
                "tools/call result must contain content. Got: " + json);

        verify(toolRegistry)
                .invokeTool(eq(ToolCapability.RECALL_MEMORY_SEARCH.toolName()), anyString(), eq("mcp-server"));
    }
}
