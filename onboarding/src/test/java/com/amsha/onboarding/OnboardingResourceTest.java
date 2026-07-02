package com.amsha.onboarding;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;

import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.List;

import com.amsha.core.llm.LlmValidationGateway;
import com.amsha.core.security.SecretVault;
import com.amsha.onboarding.dto.Step2Request;
import com.amsha.onboarding.dto.Step3Request;
import com.amsha.onboarding.dto.Step4Request;
import com.amsha.onboarding.dto.Step5Request;
import com.amsha.onboarding.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class OnboardingResourceTest {

    @InjectMock
    LlmValidationGateway mockModelFactory;

    @InjectMock
    EmbeddingModel mockEmbeddingModel;

    @InjectMock
    SecretVault mockVault;

    private static final String SID_COOKIE = "AMSHA_ONBOARDING_SID";

    @Test
    void testWizardFlow() {
        Mockito.when(mockModelFactory.validate(any(), any(), any())).thenReturn(true);

        // Manual cookie tracking - SessionFilter only works with JSESSIONID, not custom cookies
        final String[] sid = {null};

        // 1. GET /status → step=1 on fresh start
        String newSid = doGet("/onboarding/status", sid[0])
                .then()
                .statusCode(200)
                .body("step", is(1))
                .extract()
                .cookie(SID_COOKIE);
        if (newSid != null) sid[0] = newSid;

        // 8. Out-of-order guard: step/2 before step/1 → 403
        doPost("/onboarding/step/2", new Step2Request("groq", ""), sid[0])
                .then()
                .statusCode(403);

        // 2. POST /step/1 → nextStep=2
        newSid = doPost("/onboarding/step/1", null, sid[0])
                .then()
                .statusCode(200)
                .body("nextStep", is(2))
                .extract()
                .cookie(SID_COOKIE);
        if (newSid != null) sid[0] = newSid;

        // 3. POST /step/2 → nextStep=3, default model populated
        newSid = doPost("/onboarding/step/2", new Step2Request("groq", ""), sid[0])
                .then()
                .statusCode(200)
                .body("nextStep", is(3))
                .extract()
                .cookie(SID_COOKIE);
        if (newSid != null) sid[0] = newSid;

        // Status check: model auto-populated to meta-llama/llama-4-scout-17b-16e-instruct
        doGet("/onboarding/status", sid[0])
                .then()
                .statusCode(200)
                .body("model", is("meta-llama/llama-4-scout-17b-16e-instruct"));

        // 4. POST /step/3 → mocked gateway validates → nextStep=4
        newSid = doPost("/onboarding/step/3", new Step3Request("test-key"), sid[0])
                .then()
                .statusCode(200)
                .body("nextStep", is(4))
                .body("valid", is(true))
                .extract()
                .cookie(SID_COOKIE);
        if (newSid != null) sid[0] = newSid;

        // 5. POST /step/4 → nextStep=5
        newSid = doPost("/onboarding/step/4", new Step4Request("You are Amsha Agent."), sid[0])
                .then()
                .statusCode(200)
                .body("nextStep", is(5))
                .extract()
                .cookie(SID_COOKIE);
        if (newSid != null) sid[0] = newSid;

        // 6. POST /step/5 → nextStep=6
        newSid = doPost("/onboarding/step/5", new Step5Request(List.of()), sid[0])
                .then()
                .statusCode(200)
                .body("nextStep", is(6))
                .extract()
                .cookie(SID_COOKIE);
        if (newSid != null) sid[0] = newSid;

        // 7. GET /complete → done=true, redirectTo=/#/chat
        doGet("/onboarding/complete", sid[0])
                .then()
                .statusCode(200)
                .body("complete", is(true))
                .body("chatUrl", is("/#/chat"));
    }

    private Response doGet(String path, String sid) {
        var spec = given();
        if (sid != null) spec = spec.cookie(SID_COOKIE, sid);
        return spec.when().get(path);
    }

    private Response doPost(String path, Object body, String sid) {
        var spec = given().contentType(ContentType.JSON);
        if (sid != null) spec = spec.cookie(SID_COOKIE, sid);
        if (body != null) spec = spec.body(body);
        return spec.when().post(path);
    }
}
