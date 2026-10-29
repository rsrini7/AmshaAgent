package com.amsha.app.evaluation;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class AgentRunEvaluationCorpusSupport {
    private static final String TEST_DATABASE_URL = "jdbc:sqlite:"
            + Path.of("target", "app-test.db").toAbsolutePath().normalize()
            + "?busy_timeout=20000&journal_mode=WAL&transaction_mode=IMMEDIATE";
    private static final String CORPUS_RESOURCE = "/agent-run-evaluation/corpus-v2.json";
    static final String DURABLE_PAUSE_CASE_ID = "durable-pause-resume-after-restart";
    static final String ELIGIBLE_RETRY_CASE_ID = "eligible-retry-recovery-after-restart";
    private static final Set<String> CORPUS_FIELDS = Set.of("version", "cases");
    private static final Set<String> CASE_FIELDS =
            Set.of("id", "goal", "expectedAnswer", "expectedOutcome", "expectedFailureCode", "scriptRef", "inputRef");
    private static final Set<String> OUTCOMES = Set.of("passed", "failed", "skipped");
    private static final Set<String> SCRIPT_REFERENCES = Set.of(
            "finish", "unknown-tool", "delegate", "deny-approval", "tool-failure", "durable-pause", "eligible-retry");
    private static final Set<String> INPUT_REFERENCES = Set.of("valid", "malformed-json", "wrong-types");

    static String testDatabaseUrl() {
        return TEST_DATABASE_URL;
    }

    static Corpus readCorpus(Class<?> testClass, ObjectMapper mapper) throws IOException {
        try (InputStream input = testClass.getResourceAsStream(CORPUS_RESOURCE)) {
            assertNotNull(input, "Whole-run evidence corpus resource is missing");
            JsonNode corpus = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(input);
            validateCorpus(corpus);
            List<CorpusCase> cases = new ArrayList<>();
            for (JsonNode evidenceCase : corpus.path("cases")) {
                cases.add(new CorpusCase(
                        textField(evidenceCase, "id"),
                        textField(evidenceCase, "goal"),
                        textField(evidenceCase, "expectedAnswer"),
                        textField(evidenceCase, "expectedOutcome"),
                        textField(evidenceCase, "expectedFailureCode"),
                        textField(evidenceCase, "scriptRef"),
                        textField(evidenceCase, "inputRef")));
            }
            return new Corpus(corpus.path("version").asInt(), List.copyOf(cases));
        }
    }

    static List<Map<String, Object>> runCorpusValidationProbes(ObjectMapper mapper) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (ValidationProbe probe : validationProbes()) {
            boolean rejected;
            try {
                JsonNode parsed = mapper.reader()
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                        .readTree(probe.payload());
                validateCorpus(parsed);
                rejected = false;
            } catch (Exception invalidInput) {
                rejected = true;
            }
            results.add(Map.of(
                    "probeId", probe.id(),
                    "outcome", rejected ? "passed" : "failed",
                    "result", rejected ? "rejected" : "accepted"));
        }
        return List.copyOf(results);
    }

    private static List<ValidationProbe> validationProbes() {
        String validCase = "{\"id\":\"a\",\"goal\":\"g\",\"expectedAnswer\":\"a\","
                + "\"expectedOutcome\":\"passed\",\"expectedFailureCode\":\"none\","
                + "\"scriptRef\":\"finish\",\"inputRef\":\"valid\"}";
        return List.of(
                new ValidationProbe("malformed-json", "{\"version\":2,\"cases\":["),
                new ValidationProbe("wrong-field-type", "{\"version\":\"2\",\"cases\":[]}"),
                new ValidationProbe("missing-identifier", "{\"version\":2,\"cases\":[{\"goal\":\"g\"}]}"),
                new ValidationProbe("duplicate-json-identifier", "{\"version\":2,\"version\":2,\"cases\":[]}"),
                new ValidationProbe(
                        "duplicate-case-identifier", "{\"version\":2,\"cases\":[" + validCase + "," + validCase + "]}"),
                new ValidationProbe(
                        "unknown-script-reference",
                        "{\"version\":2,\"cases\":[{\"id\":\"a\",\"goal\":\"g\","
                                + "\"expectedAnswer\":\"a\",\"expectedOutcome\":\"passed\","
                                + "\"expectedFailureCode\":\"none\",\"scriptRef\":\"missing\","
                                + "\"inputRef\":\"valid\"}] }"),
                new ValidationProbe(
                        "unknown-input-reference",
                        "{\"version\":2,\"cases\":[{\"id\":\"a\",\"goal\":\"g\","
                                + "\"expectedAnswer\":\"a\",\"expectedOutcome\":\"passed\","
                                + "\"expectedFailureCode\":\"none\",\"scriptRef\":\"finish\","
                                + "\"inputRef\":\"missing\"}] }"),
                new ValidationProbe(
                        "partial-record",
                        "{\"version\":2,\"cases\":[{\"id\":\"a\",\"goal\":\"g\","
                                + "\"scriptRef\":\"finish\",\"inputRef\":\"valid\"}] }"));
    }

    private static void validateCorpus(JsonNode corpus) {
        requireExactFields(corpus, CORPUS_FIELDS, "corpus");
        if (!corpus.path("version").isInt() || corpus.path("version").asInt() != 2) {
            throw new IllegalArgumentException("Unsupported corpus version");
        }
        JsonNode cases = corpus.path("cases");
        if (!cases.isArray() || cases.isEmpty()) {
            throw new IllegalArgumentException("Corpus cases must be a non-empty array");
        }
        Set<String> identifiers = new HashSet<>();
        for (JsonNode evidenceCase : cases) {
            requireExactFields(evidenceCase, CASE_FIELDS, "case");
            String id = textField(evidenceCase, "id");
            String scriptRef = textField(evidenceCase, "scriptRef");
            String inputRef = textField(evidenceCase, "inputRef");
            textField(evidenceCase, "goal");
            textField(evidenceCase, "expectedAnswer");
            String expectedOutcome = textField(evidenceCase, "expectedOutcome");
            String expectedFailureCode = textField(evidenceCase, "expectedFailureCode");
            if (!OUTCOMES.contains(expectedOutcome)) {
                throw new IllegalArgumentException("Unsupported expected outcome");
            }
            boolean expectsFailure = "failed".equals(expectedOutcome);
            boolean hasFailureCode = !"none".equals(expectedFailureCode);
            if (expectsFailure != hasFailureCode) {
                throw new IllegalArgumentException("Expected failure code must agree with expected outcome");
            }
            if (!identifiers.add(id)) {
                throw new IllegalArgumentException("Duplicate case identifier");
            }
            if (!SCRIPT_REFERENCES.contains(scriptRef) || !INPUT_REFERENCES.contains(inputRef)) {
                throw new IllegalArgumentException("Unknown corpus reference");
            }
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> expected, String location) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(location + " must be an object");
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(location + " has missing or unknown fields");
        }
    }

    private static String textField(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Corpus text field is missing or invalid");
        }
        return value.asText();
    }

    static void writeReport(
            ObjectMapper mapper,
            int corpusVersion,
            List<Map<String, Object>> caseResults,
            List<Map<String, Object>> validationResults)
            throws IOException {
        Path reportDirectory = Path.of("target", "agent-run-evaluation");
        Files.createDirectories(reportDirectory);
        long passed = caseResults.stream()
                .filter(result -> "passed".equals(result.get("outcome")))
                .count();
        long failed = caseResults.stream()
                .filter(result -> "failed".equals(result.get("outcome")))
                .count();
        long skipped = caseResults.stream()
                .filter(result -> "skipped".equals(result.get("outcome")))
                .count();
        long unknownCosts = caseResults.stream()
                .filter(result -> "unknown".equals(result.get("providerCost")))
                .count();
        Map<String, Object> report = Map.of(
                "reportVersion", 2,
                "corpusVersion", corpusVersion,
                "runId", UUID.randomUUID().toString(),
                "startedAt", Instant.now().toString(),
                "provider", "scripted-test-model",
                "summary",
                        Map.of(
                                "total", caseResults.size(),
                                "passed", passed,
                                "failed", failed,
                                "skipped", skipped,
                                "unknownCost", unknownCosts,
                                "validationProbes", validationResults.size()),
                "cases", List.copyOf(caseResults),
                "corpusValidation", List.copyOf(validationResults));
        Files.writeString(
                reportDirectory.resolve("corpus-v" + corpusVersion + "-report.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    static String durablePauseSessionId(ObjectMapper mapper) throws IOException {
        JsonNode report = mapper.readTree(reportPath().toFile());
        for (JsonNode evidenceCase : report.path("cases")) {
            if (DURABLE_PAUSE_CASE_ID.equals(evidenceCase.path("caseId").asText())) {
                String sessionId = evidenceCase.path("sessionCorrelationId").asText();
                if (!sessionId.isBlank() && !"unknown".equals(sessionId)) {
                    return sessionId;
                }
            }
        }
        throw new IllegalStateException("Durable pause evidence is missing its session correlation ID");
    }

    static String eligibleRetrySessionId(ObjectMapper mapper) throws IOException {
        JsonNode report = mapper.readTree(reportPath().toFile());
        for (JsonNode evidenceCase : report.path("cases")) {
            if (ELIGIBLE_RETRY_CASE_ID.equals(evidenceCase.path("caseId").asText())) {
                String sessionId = evidenceCase.path("sessionCorrelationId").asText();
                if (!sessionId.isBlank() && !"unknown".equals(sessionId)) {
                    return sessionId;
                }
            }
        }
        throw new IllegalStateException("Eligible retry evidence is missing its session correlation ID");
    }

    static void recordDurableResume(
            ObjectMapper mapper,
            String outcome,
            String failureKind,
            String toolBehavior,
            String approvalBehavior,
            String checkpointAfterRestartStatus,
            int decisionStatus,
            String decisionErrorCode,
            long additionalLatencyMs,
            int additionalModelCalls,
            int additionalToolExecutions,
            int stepCount,
            String lastStepAction)
            throws IOException {
        ObjectNode report = (ObjectNode) mapper.readTree(reportPath().toFile());
        ArrayNode cases = (ArrayNode) report.path("cases");
        ObjectNode durableCase = null;
        for (JsonNode evidenceCase : cases) {
            if (DURABLE_PAUSE_CASE_ID.equals(evidenceCase.path("caseId").asText())) {
                durableCase = (ObjectNode) evidenceCase;
                break;
            }
        }
        if (durableCase == null) {
            throw new IllegalStateException("Durable pause case is missing from the report");
        }
        durableCase.put("outcome", outcome);
        durableCase.put("failureKind", failureKind);
        durableCase.put("skipReason", "none");
        durableCase.put("decisionHttpStatus", decisionStatus);
        durableCase.put("decisionErrorCode", decisionErrorCode);
        durableCase.put("safetyViolations", 0);
        durableCase.put("toolBehavior", toolBehavior);
        durableCase.put("approvalBehavior", approvalBehavior);
        durableCase.put("checkpointBeforeRestartStatus", "AWAITING_APPROVAL");
        durableCase.put("recoveryMechanism", "stale-approval-denial");
        durableCase.put("checkpointAfterRestartStatus", checkpointAfterRestartStatus);
        durableCase.put("stepCount", stepCount);
        durableCase.put("lastStepAction", lastStepAction);
        durableCase.put("lastStepErrorCode", "unknown");
        durableCase.put("lastStepKind", "none");
        durableCase.put("modelCalls", durableCase.path("modelCalls").asInt() + additionalModelCalls);
        durableCase.put("toolExecutions", durableCase.path("toolExecutions").asInt() + additionalToolExecutions);
        durableCase.put("latencyMs", durableCase.path("latencyMs").asLong() + additionalLatencyMs);
        durableCase.put("latencyStatus", "measured");

        refreshReportSummary(report, cases);
        Files.writeString(reportPath(), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    static void recordEligibleRetryResume(
            ObjectMapper mapper,
            String outcome,
            String failureKind,
            String checkpointAfterRestartStatus,
            long additionalLatencyMs,
            int additionalModelCalls,
            int additionalToolExecutions,
            int stepCount,
            String lastStepAction)
            throws IOException {
        ObjectNode report = (ObjectNode) mapper.readTree(reportPath().toFile());
        ArrayNode cases = (ArrayNode) report.path("cases");
        ObjectNode retryCase = null;
        for (JsonNode evidenceCase : cases) {
            if (ELIGIBLE_RETRY_CASE_ID.equals(evidenceCase.path("caseId").asText())) {
                retryCase = (ObjectNode) evidenceCase;
                break;
            }
        }
        if (retryCase == null) {
            throw new IllegalStateException("Eligible retry case is missing from report");
        }
        retryCase.put("outcome", outcome);
        retryCase.put("failureKind", failureKind);
        retryCase.put("skipReason", "none");
        retryCase.put("safetyViolations", 0);
        retryCase.put("toolBehavior", "read-only-tool-executed-once-before-restart;no-duplicate-after-retry");
        retryCase.put("approvalBehavior", "approval-not-required;retry-resumed-after-restart");
        retryCase.put("checkpointBeforeRestartStatus", "AWAITING_RETRY");
        retryCase.put("recoveryMechanism", "rate-limit-retry-scheduler");
        retryCase.put("checkpointAfterRestartStatus", checkpointAfterRestartStatus);
        retryCase.put("stepCount", stepCount);
        retryCase.put("lastStepAction", lastStepAction);
        retryCase.put("lastStepErrorCode", "none");
        retryCase.put("lastStepKind", "none");
        retryCase.put("modelCalls", retryCase.path("modelCalls").asInt() + additionalModelCalls);
        retryCase.put("toolExecutions", retryCase.path("toolExecutions").asInt() + additionalToolExecutions);
        retryCase.put("latencyMs", retryCase.path("latencyMs").asLong() + additionalLatencyMs);
        retryCase.put("latencyStatus", "measured");
        refreshReportSummary(report, cases);
        Files.writeString(reportPath(), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static void refreshReportSummary(ObjectNode report, ArrayNode cases) {
        long passed = countCases(cases, "passed");
        long failed = countCases(cases, "failed");
        long skipped = countCases(cases, "skipped");
        long unknownCosts = 0;
        for (JsonNode evidenceCase : cases) {
            if ("unknown".equals(evidenceCase.path("providerCost").asText())) {
                unknownCosts++;
            }
        }
        ObjectNode summary = (ObjectNode) report.path("summary");
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        summary.put("total", cases.size());
        summary.put("unknownCost", unknownCosts);
    }

    private static long countCases(ArrayNode cases, String outcome) {
        long count = 0;
        for (JsonNode evidenceCase : cases) {
            if (outcome.equals(evidenceCase.path("outcome").asText())) {
                count++;
            }
        }
        return count;
    }

    private static Path reportPath() {
        return Path.of("target", "agent-run-evaluation", "corpus-v2-report.json");
    }

    record Corpus(int version, List<CorpusCase> cases) {}

    record CorpusCase(
            String id,
            String goal,
            String expectedAnswer,
            String expectedOutcome,
            String expectedFailureCode,
            String scriptRef,
            String inputRef) {}

    private record ValidationProbe(String id, String payload) {}

    static final class CaseEvidence {
        private final String caseId;
        private final String expectedOutcome;
        private final String expectedFailureCode;
        private String outcome = "running";
        private String failureKind = "none";
        private Object runHttpStatus = "unknown";
        private String runErrorCode = "none";
        private String runResponseKind = "unknown";
        private String sessionCorrelationId = "unknown";
        private Object stepCount = "unknown";
        private String lastStepAction = "unknown";
        private String lastStepErrorCode = "unknown";
        private String lastStepKind = "unknown";
        private Object decisionHttpStatus = "unknown";
        private String decisionErrorCode = "unknown";
        private Object safetyViolations = "unknown";
        private String toolBehavior = "not-observed";
        private String approvalBehavior = "not-requested";
        private Object latencyMs = "unknown";
        private String latencyStatus = "unknown";
        private String skipReason = "none";

        CaseEvidence(String caseId, String expectedOutcome, String expectedFailureCode) {
            this.caseId = caseId;
            this.expectedOutcome = expectedOutcome;
            this.expectedFailureCode = expectedFailureCode;
        }

        void runStatus(int status, String errorCode, String responseKind) {
            runHttpStatus = status;
            runErrorCode = errorCode;
            runResponseKind = responseKind;
        }

        void sessionId(String value) {
            sessionCorrelationId = value;
        }

        void stepCount(int value) {
            stepCount = value;
        }

        void lastStepAction(String value) {
            lastStepAction = value;
        }

        void lastStepErrorCode(String value) {
            lastStepErrorCode = value;
        }

        void lastStepKind(String value) {
            lastStepKind = value;
        }

        void decisionStatus(int status, String errorCode) {
            decisionHttpStatus = status;
            decisionErrorCode = errorCode;
        }

        void safetyViolations(int count) {
            safetyViolations = count;
        }

        void toolBehavior(String behavior) {
            toolBehavior = behavior;
        }

        void approvalBehavior(String behavior) {
            approvalBehavior = behavior;
        }

        void measuredLatency(long value) {
            latencyMs = value;
            latencyStatus = "measured";
        }

        void passed() {
            outcome = "passed";
            failureKind = "none";
        }

        void completeIfPending() {
            if ("running".equals(outcome)) {
                passed();
            }
        }

        void skipped(String reason) {
            outcome = "skipped";
            failureKind = "none";
            skipReason = reason;
        }

        void failed(String kind) {
            outcome = "failed";
            failureKind = kind;
        }

        Map<String, Object> toMap(int reasoningCalls, int toolExecutions) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("caseId", caseId);
            result.put("expectedOutcome", expectedOutcome);
            result.put("expectedFailureCode", expectedFailureCode);
            result.put("outcome", outcome);
            result.put("failureKind", failureKind);
            result.put("skipReason", skipReason);
            result.put("runHttpStatus", runHttpStatus);
            result.put("runErrorCode", runErrorCode);
            result.put("runResponseKind", runResponseKind);
            result.put("sessionCorrelationId", sessionCorrelationId);
            result.put("stepCount", stepCount);
            result.put("lastStepAction", lastStepAction);
            result.put("lastStepErrorCode", lastStepErrorCode);
            result.put("lastStepKind", lastStepKind);
            result.put("decisionHttpStatus", decisionHttpStatus);
            result.put("decisionErrorCode", decisionErrorCode);
            result.put("safetyViolations", safetyViolations);
            result.put("toolBehavior", toolBehavior);
            result.put("approvalBehavior", approvalBehavior);
            result.put("latencyMs", latencyMs);
            result.put("latencyStatus", latencyStatus);
            result.put("modelCalls", reasoningCalls);
            result.put("toolExecutions", toolExecutions);
            result.put("providerCost", reasoningCalls == 0 ? "0" : "unknown");
            result.put("costStatus", reasoningCalls == 0 ? "known-zero" : "unpriced");
            result.put(
                    "costReason",
                    reasoningCalls == 0 ? "no-reasoning-provider-call" : "scripted-test-model-has-no-price-metadata");
            return Map.copyOf(result);
        }
    }
}
