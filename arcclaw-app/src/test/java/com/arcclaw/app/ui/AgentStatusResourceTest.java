package com.arcclaw.app.ui;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.mockito.Mockito.when;

import java.util.List;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AgentStatusResourceTest {

    @InjectMock
    AgentStateStore stateStore;

    @Test
    void testListAgents() {
        when(stateStore.getAll()).thenReturn(List.of(
            new AgentStateStore.AgentState("coordinator", "idle", "none","sessionid")
        ));

        given()
          .header("X-ArcClaw-API-Key", "key-internal-changeme")
          .when().get("/api/v1/agents")
          .then()
             .statusCode(200)
             .body("[0].agentName", is("coordinator"))
             .body("[0].status", is("idle"));
    }
}
