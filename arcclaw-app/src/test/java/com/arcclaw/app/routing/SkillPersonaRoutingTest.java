package com.arcclaw.app.routing;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;

import jakarta.inject.Inject;

import com.arcclaw.app.security.ApiKeyRegistry;
import com.arcclaw.channel.api.IntentDetector;
import com.arcclaw.core.audit.TelemetryAuditService;
import com.arcclaw.core.routing.IntentDetectorService;
import com.arcclaw.core.util.HttpConstants;
import com.arcclaw.persistence.api.SkillDto;
import com.arcclaw.persistence.api.SkillStorePort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

@QuarkusTest
class SkillPersonaRoutingTest {

    @Inject
    SkillStorePort skillStore;

    @Inject
    IntentDetector detector;

    @InjectMock
    IntentDetectorService intentDetectorService;

    @InjectMock
    TelemetryAuditService telemetryAuditService;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @BeforeEach
    void setup() {
        apiKeyRegistry.register("test-key", "key-internal-changeme");

        skillStore.deleteAll();
        
        String yaml = """
                ---
                name: code-reviewer
                description: Reviews PRs
                trigger-patterns:
                  - "review.*code"
                  - "check.*code"
                ---
                PROMPT
                """;
        skillStore.save(new SkillDto("u1", "code-reviewer", yaml, Instant.now(), Instant.now()));

        // Mock the LLM fallback to avoid log noise and real network calls
        Mockito.when(intentDetectorService.classify(ArgumentMatchers.anyString()))
            .thenReturn("coordinator");

        // Force reload so the registry sees the new DB skill
        given()
            .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
            .get("/api/v1/skills/refresh").then().statusCode(200);
    }

    @Test
    void testMessageTriggersSkillRouting() {
        // Fast-path match
        String role = detector.classify("Can you review my code please?");
        assertEquals("skill:code-reviewer", role);
    }

    @Test
    void testMessageFallsBackWhenNoSkillMatch() {
        // No match in registry - falls back to LLM classifier (which might be mocked or real, 
        // but it certainly won't return skill:code-reviewer for a greeting)
        String role = detector.classify("Hello there");
        assertNotEquals("skill:code-reviewer", role);
    }
}
