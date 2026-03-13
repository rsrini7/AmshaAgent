package com.arcclaw.app.skill;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.audit.TelemetryAuditService;
import com.arcclaw.core.skill.AgentSkillRepository;
import com.arcclaw.core.util.HttpConstants;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class SkillApiTest {

    @Inject
    AgentSkillRepository repository;

    @InjectMock
    TelemetryAuditService telemetryAuditService;

    @BeforeEach
    void setup() {
        // Run in a programmatic transaction to ensure it's committed before REST calls
        repository.deleteAll();
        // Defensive refresh to start with empty registry
        given()
            .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
            .get("/api/v1/skills/refresh").then().statusCode(200);
    }

    @Test
    void testListEmptySkills() {
        given()
            .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
            .when().get("/api/v1/skills")
            .then()
            .statusCode(200)
            .body("size()", is(0));
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

        given()
            .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
            .contentType("text/plain")
            .body(yaml)
            .when().post("/api/v1/skills")
            .then()
            .statusCode(201)
            .body("message", containsString("ping-skill"));

        given()
            .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
            .when().get("/api/v1/skills")
            .then()
            .statusCode(200)
            .body("size()", is(1))
            .body("[0].name", is("ping-skill"))
            .body("[0].source", is("database"));
    }

    @Test
    void testUpsertInvalidYamlFails() {
        given()
            .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
            .contentType("text/plain")
            .body("invalid missing dashes")
            .when().post("/api/v1/skills")
            .then()
            .statusCode(400)
            .body("message", containsString("exactly two ---"));
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
        given()
            .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
            .contentType("text/plain")
            .body(yaml)
            .when().post("/api/v1/skills")
            .then()
            .statusCode(201);

        given()
            .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
            .when().delete("/api/v1/skills/target-skill")
            .then()
            .statusCode(200);

        given()
            .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
            .when().get("/api/v1/skills")
            .then()
            .log().body()
            .statusCode(200)
            .body("size()", is(0));
    }

    @Test
    void testDeleteNonExistentSkill() {
        given()
            .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
            .when().delete("/api/v1/skills/does-not-exist")
            .then()
            .statusCode(404);
    }
}
