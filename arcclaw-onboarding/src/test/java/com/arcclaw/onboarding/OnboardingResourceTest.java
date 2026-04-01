package com.arcclaw.onboarding;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;

import com.arcclaw.core.llm.LlmValidationGateway;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.onboarding.dto.Step2Request;
import com.arcclaw.onboarding.dto.Step3Request;
import com.arcclaw.onboarding.dto.Step4Request;
import com.arcclaw.onboarding.dto.Step5Request;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.filter.session.SessionFilter;
import io.restassured.http.ContentType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
class OnboardingResourceTest {

    @InjectMock
    LlmValidationGateway mockModelFactory;

    @InjectMock
    SecretVault mockVault;

    @Test
    void testWizardFlow() {
        // Mock LlmValidationGateway
        Mockito.when(mockModelFactory.validate(any(), any(), any())).thenReturn(true);
        
        SessionFilter sessionFilter = new SessionFilter();

        // 1. GET status -> step=1 on fresh start
        given()
          .filter(sessionFilter)
          .when().get("/onboarding/status")
          .then()
             .statusCode(200)
             .body("step", is(1));

        // 8. Verify 403 if steps attempted out of order (e.g., POST /step/3 before /step/2)
        given()
          .filter(sessionFilter)
          .contentType(ContentType.JSON)
          .body(new Step2Request("groq", ""))
          .when().post("/onboarding/step/2")
          .then()
             .statusCode(403);

        // 2. POST /onboarding/step/1 -> returns nextStep=2
        given()
          .filter(sessionFilter)
          .contentType(ContentType.JSON)
          .when().post("/onboarding/step/1")
          .then()
             .statusCode(200)
             .body("nextStep", is(2));

        // 3. POST /onboarding/step/2 with {"provider":"groq"} -> returns nextStep=3, default model populated
        given()
          .filter(sessionFilter)
          .contentType(ContentType.JSON)
          .body(new Step2Request("groq", ""))
          .when().post("/onboarding/step/2")
          .then()
             .statusCode(200)
             .body("nextStep", is(3));

        // Verify status model auto population
        given()
          .filter(sessionFilter)
          .when().get("/onboarding/status")
          .then()
             .statusCode(200)
             .body("model", is("llama-3.1-8b-instant"));

        // 4. POST /onboarding/step/3 with {"apiKey":"test-key"} -> mocked gateway returns true -> returns nextStep=4
        given()
          .filter(sessionFilter)
          .contentType(ContentType.JSON)
          .body(new Step3Request("test-key"))
          .when().post("/onboarding/step/3")
          .then()
             .statusCode(200)
             .body("nextStep", is(4))
             .body("credentialsValidated", is(true));

        // 5. POST /onboarding/step/4 with {"systemPrompt":"You are ArcClaw."} -> returns nextStep=5
        given()
          .filter(sessionFilter)
          .contentType(ContentType.JSON)
          .body(new Step4Request("You are ArcClaw."))
          .when().post("/onboarding/step/4")
          .then()
             .statusCode(200)
             .body("nextStep", is(5));

        // 6. POST /onboarding/step/5 with {"mcpServers":[]} -> returns nextStep=6
        given()
          .filter(sessionFilter)
          .contentType(ContentType.JSON)
          .body(new Step5Request(List.of()))
          .when().post("/onboarding/step/5")
          .then()
             .statusCode(200)
             .body("nextStep", is(6));

        // 7. GET /onboarding/complete -> returns {"done":true, "redirectTo":"/#/chat", ...}
        given()
          .filter(sessionFilter)
          .when().get("/onboarding/complete")
          .then()
             .statusCode(200)
             .body("done", is(true))
             .body("redirectTo", is("/#/chat"));
    }
}
