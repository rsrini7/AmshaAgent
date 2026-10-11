package com.amsha.app.classifier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.core.util.HttpConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * End-to-end proof that {@code POST /evidence/run} exercises the registered corpus through the single
 * audited egress path and returns a payload-free summary, while the plane stays Deferred (no
 * {@code DecisionEngine} bean).
 */
@QuarkusTest
@QuarkusTestResource(DecisionProviderFixture.class)
@TestProfile(DecisionEvaluationProfiles.EvaluationEnabled.class)
class DecisionEvidenceIT {

    private static final String INTERNAL = "key-internal-changeme";

    /**
     * The shipped corpus is refused by the evidence path until it is re-scoped to v2.
     *
     * <p>Five of its cases carry case-level criteria that this path never sends to the provider — the shadow
     * comparison harness appends that text to the goal/message, {@code DecisionEvidenceRunner} does not — so
     * their expected labels are not achievable here and measuring M1/M2 on them would score the prompt
     * difference rather than the model. The runner now refuses such a corpus outright
     * ({@code CORPUS_NOT_MEASURABLE}) rather than silently ignoring the field, so every test below that
     * requires a completed 200 run is blocked until the re-scope lands. Their assertions are deliberately
     * kept intact: they are the acceptance criteria for corpus v2.
     */
    private static final String BLOCKED_ON_CORPUS_V2 =
            "blocked on the corpus-v2 re-scope: the shipped corpus carries case-level criteria the evidence "
                    + "path does not deliver, so DecisionEvidenceRunner refuses it (CORPUS_NOT_MEASURABLE)";

    @Inject
    Instance<DecisionEngine> decisionEngines;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @BeforeEach
    void resetFixture() {
        apiKeyRegistry.register("internal", INTERNAL);
        DecisionProviderFixture.reset();
    }

    @Test
    void thePlaneStaysStructurallyInert() {
        assertFalse(
                decisionEngines.isResolvable(), "no DecisionEngine bean may be registered while the plane is Deferred");
    }

    @Test
    void theEvidenceRunIsRefusedUntilTheCorpusIsRescoped() {
        // This is the guard that forces the corpus-v2 re-scope. It must fail closed rather than silently drop
        // the field: five shipped cases were calibrated with case-level criteria this path never sends, so a
        // run here would score the prompt difference rather than the model. The refusal is a 409 (the request
        // is well-formed; the shipped artifact and this path's prompt are in conflict) and it must not egress.
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(409)
                .body("error", equalTo("CORPUS_NOT_MEASURABLE"));

        assertEquals(0, DecisionProviderFixture.requestCount(), "a refused evidence run must not egress");
    }

    @Test
    @Disabled(BLOCKED_ON_CORPUS_V2)
    void runsTheRegisteredCorpusAndReturnsOnlyAggregateEvidence() throws IOException {
        String json = given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        JsonNode summary = new ObjectMapper().readTree(json);

        assertEquals(1, summary.get("corpusVersion").asInt());
        assertEquals(30, summary.get("caseCount").asInt());
        assertEquals(
                Set.of("corpusVersion", "attribution", "caseCount", "m1", "m2", "m3", "m4", "m5"), fieldNames(summary));
        assertFalse(summary.has("results"));
        assertFalse(json.contains("REQUIRE_APPROVAL"), json);
        assertFalse(json.contains("LOW_RISK"), json);
    }

    @Test
    @Disabled(BLOCKED_ON_CORPUS_V2)
    void attributesEveryRunWithItsRunIdProviderAndConfiguredModel() throws IOException {
        String json = given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        JsonNode attribution = new ObjectMapper().readTree(json).get("attribution");

        assertEquals(Set.of("runId", "providerId", "configuredModel", "lane"), fieldNames(attribution));
        assertEquals("laya", attribution.get("providerId").asText());
        assertEquals("laya-multilingual", attribution.get("configuredModel").asText());
        assertEquals("SELF_HOSTED", attribution.get("lane").asText());
        assertTrue(attribution.get("runId").asText().matches("[0-9a-f-]{36}"));
    }

    @Test
    @Disabled(BLOCKED_ON_CORPUS_V2)
    void twoRunsAreDistinguishableByTheirRunId() {
        // The endpoint re-runs the corpus per request and M4's p95 is wall-clock, so two responses are
        // separate measurements. Without distinct run identifiers they could not be told apart.
        String first = runId();
        String second = runId();

        assertNotNull(first, "the response must identify its run");
        assertNotNull(second, "the response must identify its run");
        assertNotEquals(first, second, "each response must identify its own run");
    }

    @Test
    @Disabled(BLOCKED_ON_CORPUS_V2)
    void aggregateDiagnosticsAreOptInAndExposeNoCaseLevelData() throws IOException {
        String defaultJson = given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertFalse(new ObjectMapper().readTree(defaultJson).has("diagnostics"));

        String diagnosticJson = given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .queryParam("diagnostics", "true")
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        JsonNode summary = new ObjectMapper().readTree(diagnosticJson);
        JsonNode diagnostics = summary.get("diagnostics");

        assertEquals(
                Set.of(
                        "evaluatedCaseCount",
                        "refusedCaseCount",
                        "transformErrorCaseCount",
                        "skippedCaseCount",
                        "providerUsage"),
                fieldNames(diagnostics));
        assertEquals(25, diagnostics.get("evaluatedCaseCount").asInt());
        assertEquals(5, diagnostics.get("skippedCaseCount").asInt());
        assertEquals(1, diagnostics.get("providerUsage").size());
        JsonNode usage = diagnostics.get("providerUsage").get(0);
        assertEquals(
                Set.of(
                        "providerId",
                        "model",
                        "lane",
                        "evaluatedCaseCount",
                        "inputTokenReportedCaseCount",
                        "outputTokenReportedCaseCount",
                        "totalInputTokens",
                        "totalOutputTokens"),
                fieldNames(usage));
        assertEquals("laya", usage.get("providerId").asText());
        assertEquals("laya-multilingual", usage.get("model").asText());
        assertEquals(25, usage.get("evaluatedCaseCount").asInt());
        assertTrue(usage.get("inputTokenReportedCaseCount").asInt() > 0);
        assertEquals(
                usage.get("inputTokenReportedCaseCount").asInt() * 40L,
                usage.get("totalInputTokens").asLong());
        assertEquals(
                usage.get("outputTokenReportedCaseCount").asInt() * 8L,
                usage.get("totalOutputTokens").asLong());
        assertFalse(summary.has("results"));
        assertFalse(diagnosticJson.contains("caseId"), diagnosticJson);
        assertFalse(diagnosticJson.contains("expectedLabel"), diagnosticJson);
        assertFalse(diagnosticJson.contains("observedLabel"), diagnosticJson);
        assertFalse(diagnosticJson.contains("policyResolution"), diagnosticJson);
        assertFalse(diagnosticJson.contains("stateJson"), diagnosticJson);
        assertFalse(diagnosticJson.contains("user_message"), diagnosticJson);
    }

    @Test
    void anUnregisteredCorpusVersionIsRefused() {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .queryParam("version", 999)
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(400);
    }

    @Test
    @Disabled(BLOCKED_ON_CORPUS_V2)
    void rendersTheSummaryAsAPayloadFreeMarkdownReport() throws IOException {
        String markdown = given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .queryParam("format", "markdown")
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(200)
                .contentType(containsString("text/plain"))
                .body(containsString("# Decision-plane evidence report"))
                .body(containsString("- Provider: laya"))
                .body(containsString("- Configured model: laya-multilingual"))
                .body(containsString("| M1 false-allow |"))
                .body(containsString("## Cost per 1,000 (M5)"))
                .extract()
                .asString();

        assertFalse(markdown.contains("## Aggregate diagnostics"), markdown);
        assertFalse(markdown.contains("## Cases"), markdown);
        assertFalse(markdown.contains("REQUIRE_APPROVAL"), markdown);
        assertFalse(markdown.contains("intent-mal-01"), markdown);

        Path evidenceDir = resolveEvidenceDir();
        Files.createDirectories(evidenceDir);
        Path report = evidenceDir.resolve("decision_evidence_report_v1.md");
        Files.writeString(report, markdown, StandardCharsets.UTF_8);
        assertTrue(Files.exists(report), "the evidence report artifact is written");
    }

    @Test
    void anUnknownFormatIsRefused() {
        given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .queryParam("format", "xml")
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(400);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String runId() {
        return given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(200)
                .extract()
                .path("attribution.runId");
    }

    private static Path resolveEvidenceDir() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(".scratch"))) {
            current = current.getParent();
        }
        if (current != null && Files.exists(current.resolve(".scratch"))) {
            return current.resolve(".scratch/jev-decision-plane/evidence");
        }
        return Path.of("../.scratch/jev-decision-plane/evidence");
    }
}
