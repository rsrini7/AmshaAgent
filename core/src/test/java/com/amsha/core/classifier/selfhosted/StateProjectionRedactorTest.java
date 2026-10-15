package com.amsha.core.classifier.selfhosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.amsha.classifier.api.RedactionPolicy;
import com.amsha.classifier.api.StateProjectionPolicy;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StateProjectionRedactorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StateProjectionRedactor redactor;

    @BeforeEach
    void setUp() {
        redactor = new StateProjectionRedactor(mapper);
    }

    @Test
    void dropsExcludedFieldsCompletely() {
        String rawState = """
                {
                  "tool_name": "execute_shell",
                  "agent_role": "coder",
                  "goal": "run a build",
                  "internal_debug_secret": "do-not-leak",
                  "session_auth_token": "secret-session-abc",
                  "parameters": {
                    "cmd": "mvn clean compile"
                  }
                }
                """;

        Set<String> allowedFields = Set.of("tool_name", "agent_role", "goal", "parameters");
        StateProjectionPolicy policy = StateProjectionPolicy.of(allowedFields, RedactionPolicy.DEFAULT);

        String projectedJson = redactor.projectAndRedact(rawState, policy);

        // Allowed fields present
        assertTrue(projectedJson.contains("tool_name"));
        assertTrue(projectedJson.contains("execute_shell"));
        assertTrue(projectedJson.contains("agent_role"));
        assertTrue(projectedJson.contains("goal"));
        assertTrue(projectedJson.contains("parameters"));

        // Excluded fields completely dropped
        assertFalse(projectedJson.contains("internal_debug_secret"));
        assertFalse(projectedJson.contains("do-not-leak"));
        assertFalse(projectedJson.contains("session_auth_token"));
        assertFalse(projectedJson.contains("secret-session-abc"));
    }

    @Test
    void operationFieldPassesProjectionOnlyWhenExplicitlyAllowListed() throws Exception {
        String rawState = """
                {"agentRole":"coder","requiredOperation":"NETWORK_OUTBOUND"}
                """;
        StateProjectionPolicy includeOperation =
                StateProjectionPolicy.ofDefault(Set.of("agentRole", ToolRiskShadowContext.REQUIRED_OPERATION_FIELD));
        StateProjectionPolicy excludeOperation = StateProjectionPolicy.ofDefault(Set.of("agentRole"));

        var included = mapper.readTree(redactor.projectAndRedact(rawState, includeOperation));
        var excluded = mapper.readTree(redactor.projectAndRedact(rawState, excludeOperation));

        assertEquals(
                "NETWORK_OUTBOUND",
                included.get(ToolRiskShadowContext.REQUIRED_OPERATION_FIELD).asText());
        assertFalse(excluded.has(ToolRiskShadowContext.REQUIRED_OPERATION_FIELD));
    }

    @Test
    void redactsSecretsAndPii() {
        String testApiKey = "sk-proj-" + "abcdefghijklmnopqrstuv123456";
        String testBearer = "Bearer " + "secret-bearer-token-12345";
        String rawState = """
                {
                  "goal": "Send email to alice@example.com or call 555-123-4567 for SSN 123-45-6789",
                  "parameters": {
                    "apiKey": "%s",
                    "bearerAuth": "%s",
                    "password": "hunter2password",
                    "safeParam": "normal_value"
                  }
                }
                """.formatted(testApiKey, testBearer);

        Set<String> allowedFields = Set.of("goal", "parameters");
        StateProjectionPolicy policy =
                StateProjectionPolicy.of(allowedFields, new RedactionPolicy(true, true, Set.of()));

        String projectedJson = redactor.projectAndRedact(rawState, policy);

        // Secrets must be redacted
        assertFalse(projectedJson.contains(testApiKey));
        assertFalse(projectedJson.contains(testBearer));
        assertFalse(projectedJson.contains("hunter2password"));

        // PII must be redacted
        assertFalse(projectedJson.contains("alice@example.com"));
        assertFalse(projectedJson.contains("123-45-6789"));

        // Safe values and redacted marker must be present
        assertTrue(projectedJson.contains("normal_value"));
        assertTrue(projectedJson.contains(StateProjectionRedactor.REDACTED_MARKER));
    }

    @Test
    void redactsNestedDotDelimitedFieldPaths() {
        String rawState = """
                {
                  "tool_name": "execute_shell",
                  "parameters": {
                    "command": "git status -s",
                    "timeout": 30
                  }
                }
                """;

        Set<String> allowedFields = Set.of("tool_name", "parameters");
        // Mask specific nested path: parameters.command
        RedactionPolicy redactionPolicy = new RedactionPolicy(false, false, Set.of("parameters.command"));
        StateProjectionPolicy policy = StateProjectionPolicy.of(allowedFields, redactionPolicy);

        String projectedJson = redactor.projectAndRedact(rawState, policy);

        assertTrue(projectedJson.contains("execute_shell"));
        assertFalse(projectedJson.contains("git status -s"));
        assertTrue(projectedJson.contains("30"));
        assertTrue(projectedJson.contains(StateProjectionRedactor.REDACTED_MARKER));
    }

    @Test
    void redactsExplicitlyMaskedFields() {
        String rawState = """
                {
                  "tool_name": "database_query",
                  "query": "SELECT * FROM users",
                  "goal": "retrieve records"
                }
                """;

        Set<String> allowedFields = Set.of("tool_name", "query", "goal");
        RedactionPolicy redactionPolicy = new RedactionPolicy(false, false, Set.of("query"));
        StateProjectionPolicy policy = StateProjectionPolicy.of(allowedFields, redactionPolicy);

        String projectedJson = redactor.projectAndRedact(rawState, policy);

        assertTrue(projectedJson.contains("database_query"));
        assertFalse(projectedJson.contains("SELECT * FROM users"));
        assertTrue(projectedJson.contains(StateProjectionRedactor.REDACTED_MARKER));
    }

    @Test
    void failsClosedOnMissingOrInvalidPolicy() {
        String rawState = "{\"tool_name\": \"test\"}";

        assertThrows(IllegalStateException.class, () -> redactor.projectAndRedact(rawState, null));
    }

    @Test
    void failsClosedOnMalformedJsonInput() {
        String invalidJson = "{ not-valid-json ";
        StateProjectionPolicy policy = StateProjectionPolicy.of(Set.of("tool"), RedactionPolicy.DEFAULT);

        assertThrows(IllegalArgumentException.class, () -> redactor.projectAndRedact(invalidJson, policy));
    }

    @Test
    void failsClosedOnNullOrBlankState() {
        StateProjectionPolicy policy = StateProjectionPolicy.of(Set.of("tool"), RedactionPolicy.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> redactor.projectAndRedact(null, policy));
        assertThrows(IllegalArgumentException.class, () -> redactor.projectAndRedact("  ", policy));
    }
}
