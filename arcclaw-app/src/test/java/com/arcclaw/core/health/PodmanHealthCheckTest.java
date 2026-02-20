package com.arcclaw.core.health;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class PodmanHealthCheckTest {

    // Health endpoints are served on the Quarkus management interface (port 9001),
    // not the main HTTP port. We must target the management port explicitly.

    @Test
    void testLivenessEndpoint() {
        given()
                .port(9001)
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", is("UP"));
    }

    @Test
    void testReadinessEndpoint() {
        // Log the full response to diagnose which readiness checks are DOWN
        given()
                .port(9001)
                .when().get("/q/health/ready")
                .then()
                .log().all()
                .statusCode(200)
                .body("status", is("UP"));
    }
}
