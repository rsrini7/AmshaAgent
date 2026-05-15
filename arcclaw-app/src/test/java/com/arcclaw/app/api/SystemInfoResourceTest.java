package com.arcclaw.app.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SystemInfoResourceTest {

    @Test
    void testGetInfo_returns200WithCorrectShape() {
        given()
          .when().get("/api/v1/info")
          .then()
             .statusCode(200)
             .body("name", is("arcclaw"))
             .body("version", is("1.0.0"))
             .body("environment", anyOf(is("dev"), is("test"), is("prod")))
             .body("startedAt", notNullValue());
    }
}
