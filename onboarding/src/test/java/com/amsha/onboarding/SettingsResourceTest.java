package com.amsha.onboarding;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.amsha.core.llm.LlmValidationGateway;
import com.amsha.onboarding.dto.PromptUpdateRequest;
import com.amsha.onboarding.dto.ProviderUpdateRequest;
import com.amsha.onboarding.testutil.FlywayEnabledTestProfile;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
public class SettingsResourceTest {

    @InjectMock
    LlmValidationGateway llmValidationGateway;

    @ConfigProperty(name = "amsha.api.keys.internal", defaultValue = "key-internal-changeme")
    String internalApiKey;

    @BeforeEach
    void setup() throws Exception {
        when(llmValidationGateway.validate(anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    private RequestSpecification givenAuth() {
        return given().header("X-Amsha-API-Key", internalApiKey);
    }

    @Test
    void testGetSettings() {
        givenAuth().when().get("/settings").then().statusCode(200);
    }

    @Test
    void testGetPrompt() {
        PromptUpdateRequest req = new PromptUpdateRequest("Prompt from test");
        givenAuth()
                .contentType(ContentType.JSON)
                .body(req)
                .when()
                .patch("/settings/prompt")
                .then()
                .statusCode(200)
                .body("updated", is(true));

        givenAuth().when().get("/settings/prompt").then().statusCode(200).body("systemPrompt", is("Prompt from test"));
    }

    @Test
    void testUnauthorized() {
        given().when().get("/settings").then().statusCode(401);
    }

    @Test
    void testPatchPrompt() {
        PromptUpdateRequest req = new PromptUpdateRequest("Test prompt");
        givenAuth()
                .contentType(ContentType.JSON)
                .body(req)
                .when()
                .patch("/settings/prompt")
                .then()
                .statusCode(200)
                .body("updated", is(true));
    }

    @Test
    void testPatchProviderBadKey() throws Exception {
        when(llmValidationGateway.validate(anyString(), anyString(), anyString()))
                .thenReturn(false);

        // Provider mutation now opens a durable draft and does not write the legacy vault directly.
        ProviderUpdateRequest req = new ProviderUpdateRequest("openai", "gpt-4o", "bad-key");
        givenAuth()
                .contentType(ContentType.JSON)
                .body(req)
                .when()
                .patch("/settings/provider")
                .then()
                .statusCode(200)
                .body("draft", is("opened"));
    }

    @Test
    void testPostReset() {
        givenAuth().when().post("/settings/reset").then().statusCode(200).body("reset", is(true));
    }
}
