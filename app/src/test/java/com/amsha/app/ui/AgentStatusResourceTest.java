package com.amsha.app.ui;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.util.List;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.agent.event.AgentStateChangedEvent;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AgentStatusResourceTest {

    @InjectMock
    AgentStateStore stateStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @BeforeEach
    void setup() {
        apiKeyRegistry.register("test-key", "key-internal-changeme");
    }

    @Test
    void testListAgents() {
        when(stateStore.getAll())
                .thenReturn(List.of(new AgentStateStore.AgentState("coordinator", "idle", "none", "sessionid")));

        given().header("X-Amsha-API-Key", "key-internal-changeme")
                .when()
                .get("/api/v1/agents")
                .then()
                .statusCode(200)
                .body("[0].agentName", is("coordinator"))
                .body("[0].status", is("idle"));
    }

    @Test
    void listAgentsCalculatesTokensWithoutRestRuntime() {
        AgentStateStore localStore = new AgentStateStore();
        localStore.onAgentStateChanged(new AgentStateChangedEvent("session", "researcher", "working", "task"));
        localStore.onAgentStateChanged(new AgentStateChangedEvent(null, "coordinator", "idle", "none"));
        SessionHeartbeatTracker tracker = new SessionHeartbeatTracker();
        tracker.recordHeartbeat("session");
        tracker.recordTokens("session", 12, 8);

        AgentStatusResource resource = new AgentStatusResource();
        resource.stateStore = localStore;
        resource.sessionHeartbeatTracker = tracker;

        var agents = resource.listAgents();
        assertEquals(2, agents.size());
        assertEquals(
                20,
                agents.stream()
                        .filter(agent -> agent.agentName().equals("researcher"))
                        .findFirst()
                        .orElseThrow()
                        .tokenCount());
    }
}
