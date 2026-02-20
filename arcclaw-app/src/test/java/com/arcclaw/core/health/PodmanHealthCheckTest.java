package com.arcclaw.core.health;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.hamcrest.Matchers.is;

@QuarkusTest
class PodmanHealthCheckTest {

    @InjectMock
    PodmanHealthCheck podmanHealthCheck;

    @InjectMock
    PodmanImageHealthCheck podmanImageHealthCheck;

    @BeforeEach
    void setUp() {
        // Mocking behavior to return UP
        Mockito.when(podmanHealthCheck.call())
                .thenReturn(HealthCheckResponse.named("Podman Engine").up().build());
        Mockito.when(podmanImageHealthCheck.call())
                .thenReturn(HealthCheckResponse.named("Podman Image: test-image").up().build());
    }

    @Test
    void testLivenessEndpoint() {
        RestAssured.given()
                .when().get("/q/health/live")
                .then()
                .log().all()
                .statusCode(200)
                .body("status", is("UP"));
    }

    @Test
    void testReadinessEndpoint() {
        RestAssured.given()
                .when().get("/q/health/ready")
                .then()
                .log().all()
                .statusCode(200)
                .body("status", is("UP"));
    }
}
