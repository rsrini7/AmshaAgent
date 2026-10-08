package com.amsha.app.classifier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.classifier.api.DecisionEngine;
import com.amsha.core.util.HttpConstants;

import org.junit.jupiter.api.BeforeEach;
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
    void runsTheRegisteredCorpusAndReturnsOnlyAggregateEvidence() {
        String json = given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertTrue(json.contains("\"corpusVersion\":1"), json);
        assertTrue(json.contains("\"caseCount\":30"), json);
        assertFalse(json.contains("\"results\""), json);
        assertFalse(json.contains("REQUIRE_APPROVAL"), json);
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
    void rendersTheSummaryAsAPayloadFreeMarkdownReport() throws IOException {
        String markdown = given().header(HttpConstants.X_AMSHA_API_KEY, INTERNAL)
                .queryParam("format", "markdown")
                .post("/api/v1/admin/decision/evidence/run")
                .then()
                .statusCode(200)
                .contentType(containsString("text/plain"))
                .body(containsString("# Decision-plane evidence report"))
                .body(containsString("| M1 false-allow |"))
                .body(containsString("## Cost per 1,000 (M5)"))
                .extract()
                .asString();

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
