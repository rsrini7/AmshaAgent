package com.amsha.app.skill;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import jakarta.inject.Inject;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.audit.ComplianceAuditService;
import com.amsha.core.audit.TelemetryAuditService;
import com.amsha.core.util.HttpConstants;
import com.amsha.persistence.api.SkillStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@SuppressWarnings("java:S1192")
class SkillApiTest {

    @Inject
    SkillStorePort skillStore;

    @InjectMock
    TelemetryAuditService telemetryAuditService;

    @InjectMock
    ComplianceAuditService complianceAuditService;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @BeforeEach
    void setup() {
        apiKeyRegistry.register("test-key", "key-internal-changeme");
        // Retry deleteAll on SQLITE_BUSY — SQLite's single-writer constraint can
        // cause transient lock contention when background beans flush at startup.
        deleteAllWithRetry(3);
        // Defensive refresh to start with empty registry
        given().header(HttpConstants.X_AMSHA_API_KEY, "key-internal-changeme")
                .get("/api/v1/skills/refresh")
                .then()
                .statusCode(200);
    }

    private void deleteAllWithRetry(int maxRetries) {
        for (int i = 0; ; i++) {
            try {
                skillStore.deleteAll();
                return;
            } catch (RuntimeException e) {
                if (i >= maxRetries - 1) throw e;
                try {
                    Thread.sleep(2000L * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
    }

    @Test
    void testListEmptySkills() {
        given().header(HttpConstants.X_AMSHA_API_KEY, "key-internal-changeme")
                .when()
                .get("/api/v1/skills")
                .then()
                .statusCode(200)
                .body("findAll { it.source == 'database' }.size()", is(0));
    }

    @Test
    void testUpsertValidSkill() {
        String yaml = """
                ---
                name: ping-skill
                description: Pings the user
                ---
                You are playing ping pong
                """;

        given().header(HttpConstants.X_AMSHA_API_KEY, "key-internal-changeme")
                .contentType("text/plain")
                .body(yaml)
                .when()
                .post("/api/v1/skills")
                .then()
                .statusCode(201)
                .body("message", containsString("ping-skill"));

        given().header(HttpConstants.X_AMSHA_API_KEY, "key-internal-changeme")
                .when()
                .get("/api/v1/skills")
                .then()
                .statusCode(200)
                .body("findAll { it.source == 'database' }.size()", is(1))
                .body("find { it.name == 'ping-skill' }.source", is("database"));
    }

    @Test
    void testUpsertInvalidYamlFails() {
        given().header(HttpConstants.X_AMSHA_API_KEY, "key-internal-changeme")
                .contentType("text/plain")
                .body("invalid missing dashes")
                .when()
                .post("/api/v1/skills")
                .then()
                .statusCode(400)
                .body("detail", containsString("exactly two ---"));
    }

    @Test
    void testDeleteExistingSkill() {
        String yaml = """
                ---
                name: target-skill
                description: A skill to handle target intent
                ---
                Prompt logic here
                """;
        given().header(HttpConstants.X_AMSHA_API_KEY, "key-internal-changeme")
                .contentType("text/plain")
                .body(yaml)
                .when()
                .post("/api/v1/skills")
                .then()
                .statusCode(201);

        given().header(HttpConstants.X_AMSHA_API_KEY, "key-internal-changeme")
                .when()
                .delete("/api/v1/skills/target-skill")
                .then()
                .statusCode(200);

        given().header(HttpConstants.X_AMSHA_API_KEY, "key-internal-changeme")
                .when()
                .get("/api/v1/skills")
                .then()
                .log()
                .body()
                .statusCode(200)
                .body("findAll { it.source == 'database' }.size()", is(0));
    }

    @Test
    void testDeleteNonExistentSkill() {
        given().header(HttpConstants.X_AMSHA_API_KEY, "key-internal-changeme")
                .when()
                .delete("/api/v1/skills/does-not-exist")
                .then()
                .statusCode(404);
    }
}
