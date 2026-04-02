package com.arcclaw.onboarding;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.llm.LlmValidationGateway;
import com.arcclaw.onboarding.dto.PromptUpdateRequest;
import com.arcclaw.onboarding.dto.ProviderUpdateRequest;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
public class SettingsResourceTest {

    @InjectMock
    LlmValidationGateway llmValidationGateway;

    @BeforeEach
    void setup() throws Exception {
        when(llmValidationGateway.validate(anyString(), anyString(), anyString())).thenReturn(true);
    }

    @Test
    void testGetSettings() {
        given()
          .when().get("/settings")
          .then()
             .statusCode(200);
    }

    @Test
    void testPatchPrompt() {
        PromptUpdateRequest req = new PromptUpdateRequest("Test prompt");
        given()
          .contentType(ContentType.JSON)
          .body(req)
          .when().patch("/settings/prompt")
          .then()
             .statusCode(200)
             .body("updated", is(true));
    }

    @Test
    void testPatchProviderBadKey() throws Exception {
        when(llmValidationGateway.validate(anyString(), anyString(), anyString())).thenReturn(false);

        ProviderUpdateRequest req = new ProviderUpdateRequest("openai", "gpt-4o", "bad-key");
        given()
          .contentType(ContentType.JSON)
          .body(req)
          .when().patch("/settings/provider")
          .then()
             .statusCode(422);
    }

    @Test
    void testPostReset() {
        given()
          .when().post("/settings/reset")
          .then()
             .statusCode(200)
             .body("reset", is(true));
    }
}
