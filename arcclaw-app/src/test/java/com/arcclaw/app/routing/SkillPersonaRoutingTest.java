package com.arcclaw.app.routing;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;

import jakarta.inject.Inject;

import com.arcclaw.app.security.ApiKeyRegistry;
import com.arcclaw.channel.api.IntentClassifier;
import com.arcclaw.core.audit.TelemetryAuditService;
import com.arcclaw.core.skill.AgentSkillEntity;
import com.arcclaw.core.skill.AgentSkillRepository;
import com.arcclaw.core.util.HttpConstants;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

@QuarkusTest
class SkillPersonaRoutingTest {

    @Inject
    AgentSkillRepository repository;

    @Inject
    IntentClassifier classifier;

    @InjectMock
    IntentClassifierService intentClassifierService;

    @InjectMock
    TelemetryAuditService telemetryAuditService;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @BeforeEach
    void setup() {
        apiKeyRegistry.register("test-key", "key-internal-changeme");

        repository.deleteAll();
        
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
        AgentSkillEntity entity = new AgentSkillEntity("u1", "code-reviewer", yaml, Instant.now(), Instant.now());
        repository.saveOrUpdate(entity);

        // Mock the LLM fallback to avoid log noise and real network calls
        Mockito.when(intentClassifierService.classify(ArgumentMatchers.anyString()))
            .thenReturn("coordinator");

        // Force reload so the registry sees the new DB skill
        given()
            .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
            .get("/api/v1/skills/refresh").then().statusCode(200);
    }

    @Test
    void testMessageTriggersSkillRouting() {
        // Fast-path match
        String role = classifier.classify("Can you review my code please?");
        assertEquals("skill:code-reviewer", role);
    }

    @Test
    void testMessageFallsBackWhenNoSkillMatch() {
        // No match in registry — falls back to LLM classifier (which might be mocked or real, 
        // but it certainly won't return skill:code-reviewer for a greeting)
        String role = classifier.classify("Hello there");
        assertNotEquals("skill:code-reviewer", role);
    }
}
