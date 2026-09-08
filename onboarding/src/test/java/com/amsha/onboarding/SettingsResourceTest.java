package com.amsha.onboarding;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.amsha.core.llm.LlmValidationGateway;
import com.amsha.onboarding.dto.NetworkProfilePolicyDto;
import com.amsha.onboarding.dto.NetworkProfileUpdateRequest;
import com.amsha.onboarding.dto.PromptUpdateRequest;
import com.amsha.onboarding.dto.ProviderUpdateRequest;
import com.amsha.onboarding.dto.VoiceSettingsUpdateRequest;
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
        givenAuth()
                .when()
                .get("/settings")
                .then()
                .statusCode(200)
                .body("effectiveSource", notNullValue())
                .body("credentialState", is("MISSING"))
                .body("credentialSource", is("NONE"))
                .body("vaultPriority", is(false));
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

        // Provider mutation now commits durably through the coordinator, writing the
        // legacy LLM_* system-vault keys the runtime honors under vault-priority.
        ProviderUpdateRequest req = new ProviderUpdateRequest("openai", "gpt-4o", "bad-key");
        givenAuth()
                .contentType(ContentType.JSON)
                .body(req)
                .when()
                .patch("/settings/provider")
                .then()
                .statusCode(200)
                .body("committed", is(true))
                .body("provider", is("openai"));
    }

    @Test
    void testPatchProviderCommitsToVault() {
        when(llmValidationGateway.validate(anyString(), anyString(), anyString()))
                .thenReturn(true);

        ProviderUpdateRequest req =
                new ProviderUpdateRequest("groq", "meta-llama/llama-4-scout-17b-16e-instruct", "test-key-123");
        givenAuth()
                .contentType(ContentType.JSON)
                .body(req)
                .when()
                .patch("/settings/provider")
                .then()
                .statusCode(200)
                .body("committed", is(true));

        // Under vault-priority=false the effective provider is the configured one;
        // the durable commit still succeeds and writes the legacy LLM_* vault keys.
        givenAuth().when().get("/settings").then().statusCode(200);
    }

    @Test
    void testPatchVoice() {
        VoiceSettingsUpdateRequest req = new VoiceSettingsUpdateRequest("vivian", "AUDIO_ONLY");
        givenAuth()
                .contentType(ContentType.JSON)
                .body(req)
                .when()
                .patch("/settings/voice")
                .then()
                .statusCode(200)
                .body("voice", is("vivian"))
                .body("speechOutputMode", is("AUDIO_ONLY"));
    }

    @Test
    void testPatchVoiceInvalidRejectsWith400() {
        VoiceSettingsUpdateRequest req = new VoiceSettingsUpdateRequest("invalid_voice_xyz", "AUDIO_ONLY");
        givenAuth()
                .contentType(ContentType.JSON)
                .body(req)
                .when()
                .patch("/settings/voice")
                .then()
                .statusCode(400);

        VoiceSettingsUpdateRequest req2 = new VoiceSettingsUpdateRequest("vivian", "INVALID_MODE_XYZ");
        givenAuth()
                .contentType(ContentType.JSON)
                .body(req2)
                .when()
                .patch("/settings/voice")
                .then()
                .statusCode(400);
    }

    @Test
    void testPatchVoiceClear() {
        VoiceSettingsUpdateRequest req = new VoiceSettingsUpdateRequest("default", "default");
        givenAuth()
                .contentType(ContentType.JSON)
                .body(req)
                .when()
                .patch("/settings/voice")
                .then()
                .statusCode(200)
                .body("speechOutputMode", is("AUDIO_ONLY"));
    }

    @Test
    void testPostReset() {
        givenAuth().when().post("/settings/reset").then().statusCode(200).body("reset", is(true));
    }

    @Test
    void testGetConnectionNetworkProfileDefaults() {
        givenAuth()
                .when()
                .get("/settings/connection-network-profile")
                .then()
                .statusCode(200)
                .body("egressMode", is("ALLOW_ALL"))
                .body("tlsTrustMode", is("SYSTEM"))
                .body("requireProxy", is(false));
    }

    @Test
    void testPatchConnectionNetworkProfileStructuredAndGet() {
        String draftId = givenAuth()
                .when()
                .post("/onboarding/drafts")
                .then()
                .statusCode(200)
                .extract()
                .path("draftId");

        String connectionPayload =
                "{\"connections\":[{\"provider\":\"groq\",\"modelId\":\"llama-3-8b\",\"contextWindow\":8192,\"structuredOutputMode\":\"NATIVE\",\"capabilities\":[\"tool-calling\"]}],\"concernRoutes\":{\"agentLoop\":[0]}}";
        givenAuth()
                .contentType(ContentType.JSON)
                .body(Map.of("selectedNonSecretIds", List.of(connectionPayload)))
                .when()
                .put("/onboarding/drafts/" + draftId + "/steps/connections")
                .then()
                .statusCode(200);

        NetworkProfilePolicyDto policy = new NetworkProfilePolicyDto(
                "ALLOW_ALL",
                List.of(),
                true,
                "http://proxy.example.com:3128",
                "vault-ref-proxy",
                List.of("*.internal"),
                4500,
                25000,
                "SYSTEM",
                null);
        NetworkProfileUpdateRequest req = new NetworkProfileUpdateRequest(0, null, policy);

        givenAuth()
                .contentType(ContentType.JSON)
                .body(req)
                .when()
                .patch("/settings/connection-network-profile")
                .then()
                .statusCode(200)
                .body("updated", is(true));

        givenAuth()
                .when()
                .get("/settings/connection-network-profile")
                .then()
                .statusCode(200)
                .body("requireProxy", is(true))
                .body("proxyUrl", is("http://proxy.example.com:3128"))
                .body("connectTimeoutMs", is(4500))
                .body("readTimeoutMs", is(25000));
    }

    @Test
    void testPatchConnectionNetworkProfileValidationFailures() {
        // Timeout exceeding 300,000 ms
        NetworkProfilePolicyDto badTimeout = new NetworkProfilePolicyDto(
                "ALLOW_ALL", List.of(), false, null, null, List.of(), 300001, null, "SYSTEM", null);
        givenAuth()
                .contentType(ContentType.JSON)
                .body(new NetworkProfileUpdateRequest(0, null, badTimeout))
                .when()
                .patch("/settings/connection-network-profile")
                .then()
                .statusCode(400);

        // CUSTOM_CA missing vault ref
        NetworkProfilePolicyDto badCa = new NetworkProfilePolicyDto(
                "ALLOW_ALL", List.of(), false, null, null, List.of(), null, null, "CUSTOM_CA", "");
        givenAuth()
                .contentType(ContentType.JSON)
                .body(new NetworkProfileUpdateRequest(0, null, badCa))
                .when()
                .patch("/settings/connection-network-profile")
                .then()
                .statusCode(400);

        // requireProxy without URL
        NetworkProfilePolicyDto badProxy = new NetworkProfilePolicyDto(
                "ALLOW_ALL", List.of(), true, null, null, List.of(), null, null, "SYSTEM", null);
        givenAuth()
                .contentType(ContentType.JSON)
                .body(new NetworkProfileUpdateRequest(0, null, badProxy))
                .when()
                .patch("/settings/connection-network-profile")
                .then()
                .statusCode(400);

        // Malformed raw string
        givenAuth()
                .contentType(ContentType.JSON)
                .body(new NetworkProfileUpdateRequest(0, "not-json", null))
                .when()
                .patch("/settings/connection-network-profile")
                .then()
                .statusCode(400);
    }
}
