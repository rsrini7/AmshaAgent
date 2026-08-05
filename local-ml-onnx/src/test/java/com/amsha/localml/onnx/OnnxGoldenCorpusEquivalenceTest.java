package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.rag.EmbeddingModelConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Tag("model-smoke")
class OnnxGoldenCorpusEquivalenceTest {

    private static final String BUNDLE_ID = "all-mpnet-base-v2-e8c3b32";
    private static final Set<String> REQUIRED_PARTITIONS = Set.of(
            "batching",
            "language-han",
            "language-latin",
            "length-empty-very-short",
            "length-long-truncation",
            "unicode-symbols");

    private OnnxRuntimeManager runtimeManager;
    private OnnxSessionLeaseManager sessionLeaseManager;
    private OnnxInferenceScheduler scheduler;

    @AfterEach
    void closeRuntime() {
        if (scheduler != null) scheduler.shutdown();
        if (sessionLeaseManager != null) sessionLeaseManager.shutdown();
        if (runtimeManager != null) runtimeManager.close();
    }

    @Test
    void verifiesPythonReferenceTolerancePartitionsAndRanking() throws Exception {
        boolean releaseEvidenceRequired = Boolean.getBoolean("amsha.onnx.release-evidence");
        Path modelRoot = modelRoot();
        Path corpusFile = Path.of(System.getProperty(
                        "amsha.onnx.golden-corpus",
                        modelRoot.resolve("golden-corpus-v1.json").toString()))
                .toAbsolutePath();
        requireEvidenceFile(corpusFile, releaseEvidenceRequired, "Python-reference golden corpus");
        requireEvidenceFile(
                modelRoot.resolve(BUNDLE_ID).resolve("model.onnx"), releaseEvidenceRequired, "pinned MPNet bundle");

        ObjectMapper objectMapper = new ObjectMapper();
        GoldenCorpus corpus = objectMapper.readValue(corpusFile.toFile(), GoldenCorpus.class);
        boolean hasReferenceVectors = corpus.testCases() != null
                && !corpus.testCases().isEmpty()
                && corpus.testCases().stream()
                        .allMatch(testCase -> testCase.referenceVector() != null
                                && !testCase.referenceVector().isEmpty());
        if (releaseEvidenceRequired) {
            assertTrue(hasReferenceVectors, "release evidence must contain Python reference vectors");
        } else {
            Assumptions.assumeTrue(hasReferenceVectors, "generate the Python-reference golden corpus first");
        }
        validateCorpusContract(corpus);

        runtimeManager = new OnnxRuntimeManager();
        runtimeManager.enabled = true;
        runtimeManager.initialize();
        assertTrue(runtimeManager.isReady());

        OnnxModelBundleRegistry registry = new OnnxModelBundleRegistry(objectMapper, true, modelRoot, 1_073_741_824L);
        VerifiedOnnxModelBundle bundle = registry.loadVerified(BUNDLE_ID);
        OnnxEmbeddingBundleSpec embeddingSpec =
                bundle.manifest().embeddingSpec().orElseThrow();
        assertEquals(embeddingSpec.spaceId(), corpus.modelId());
        assertEquals(embeddingSpec.spaceRevision(), corpus.revision());
        assertEquals(embeddingSpec.vectorDimension(), corpus.vectorDimension());

        sessionLeaseManager =
                new OnnxSessionLeaseManager(runtimeManager, new OnnxTokenizerFactory(), Duration.ofSeconds(5));
        scheduler = new OnnxInferenceScheduler(1, 2, Duration.ofSeconds(10));
        scheduler.initialize();
        OnnxInferenceMetrics metrics = new OnnxInferenceMetrics(new SimpleMeterRegistry());
        metrics.initialize();
        EmbeddingModel model = createModel(registry, bundle, metrics);

        Map<String, float[]> actualVectors = embedCorpusAsBatch(model, corpus.testCases());
        Map<String, Boolean> toleranceResults = new LinkedHashMap<>();
        Map<String, Double> cosineResults = new LinkedHashMap<>();
        for (TestCase testCase : corpus.testCases()) {
            float[] actual = actualVectors.get(testCase.id());
            assertEquals(corpus.vectorDimension(), actual.length, testCase.id());
            double cosine = cosine(actual, testCase.referenceVector());
            cosineResults.put(testCase.id(), cosine);
            toleranceResults.put(testCase.id(), cosine >= corpus.cosineThreshold());
        }

        assertPartitionPassRates(corpus, toleranceResults, cosineResults);
        assertCanonicalFixtures(corpus, toleranceResults);
        assertNearestNeighborStability(corpus, actualVectors);
        if (releaseEvidenceRequired) {
            writeEvidenceReport(objectMapper, corpus, cosineResults, toleranceResults, actualVectors);
        }
    }

    @Test
    void acceptanceMathRejectsAReferenceRankingRegression() {
        Map<String, float[]> actual =
                Map.of("query", new float[] {1, 0}, "near", new float[] {0, 1}, "far", new float[] {1, 0});
        Map<String, List<Double>> reference =
                Map.of("query", List.of(1.0, 0.0), "near", List.of(0.9, 0.1), "far", List.of(0.0, 1.0));

        assertEquals(List.of("near"), nearestNeighbors("query", reference, 1));
        assertEquals(List.of("far"), nearestNeighborsFloat("query", actual, 1));
    }

    private EmbeddingModel createModel(
            OnnxModelBundleRegistry registry, VerifiedOnnxModelBundle bundle, OnnxInferenceMetrics metrics)
            throws Exception {
        return new OnnxEmbeddingProviderFactory(
                        registry,
                        runtimeManager,
                        sessionLeaseManager,
                        scheduler,
                        new OnnxEmbeddingRunner(metrics),
                        metrics,
                        262_144,
                        1_048_576,
                        32,
                        true,
                        2,
                        Duration.ofSeconds(10))
                .create(new EmbeddingModelConfig(
                        "onnx", bundle.manifest().bundleId(), "", Optional.empty(), Duration.ofSeconds(15)));
    }

    private static Map<String, float[]> embedCorpusAsBatch(EmbeddingModel model, List<TestCase> testCases) {
        Map<String, float[]> vectors = new LinkedHashMap<>();
        List<TestCase> nonEmpty = testCases.stream()
                .filter(testCase -> !testCase.input().isEmpty())
                .toList();
        List<Embedding> batch = model.embedAll(nonEmpty.stream()
                        .map(testCase -> TextSegment.from(testCase.input()))
                        .toList())
                .content();
        assertEquals(nonEmpty.size(), batch.size(), "embedAll must preserve batch cardinality");
        for (int index = 0; index < nonEmpty.size(); index++) {
            vectors.put(nonEmpty.get(index).id(), batch.get(index).vector());
        }
        testCases.stream()
                .filter(testCase -> testCase.input().isEmpty())
                .forEach(testCase -> vectors.put(
                        testCase.id(), model.embed(testCase.input()).content().vector()));
        return vectors;
    }

    private static void validateCorpusContract(GoldenCorpus corpus) {
        assertEquals("1.0.0", corpus.version());
        assertTrue(corpus.vectorDimension() > 0);
        double minimumCosine =
                switch (corpus.precision()) {
                    case "FP32" -> 0.9999;
                    case "INT8" -> 0.995;
                    default -> throw new AssertionError("unsupported corpus precision: " + corpus.precision());
                };
        assertTrue(corpus.cosineThreshold() >= minimumCosine, "cosine threshold weakens the ADR contract");
        assertTrue(corpus.minimumPartitionPassRate() >= 0.995, "partition pass rate weakens the ADR contract");
        assertTrue(corpus.nearestNeighborK() > 0);
        assertEquals(REQUIRED_PARTITIONS, new HashSet<>(corpus.requiredPartitions()));

        Set<String> ids = new HashSet<>();
        Set<String> coveredPartitions = new HashSet<>();
        for (TestCase testCase : corpus.testCases()) {
            assertTrue(ids.add(testCase.id()), "duplicate test-case ID: " + testCase.id());
            assertEquals(corpus.vectorDimension(), testCase.referenceVector().size(), testCase.id());
            assertTrue(testCase.referenceVector().stream().allMatch(Double::isFinite), testCase.id());
            assertFalse(testCase.partitions().isEmpty(), "test case has no partition: " + testCase.id());
            assertTrue(
                    REQUIRED_PARTITIONS.containsAll(testCase.partitions()),
                    "test case declares an unknown partition: " + testCase.id());
            coveredPartitions.addAll(testCase.partitions());
        }
        assertTrue(coveredPartitions.containsAll(REQUIRED_PARTITIONS));
        assertTrue(corpus.testCases().stream().anyMatch(TestCase::canonical));
        for (TestCase testCase : corpus.testCases()) {
            assertTrue(ids.containsAll(testCase.relevantIds()), "unknown relevant ID for " + testCase.id());
        }
    }

    private static void assertPartitionPassRates(
            GoldenCorpus corpus, Map<String, Boolean> results, Map<String, Double> cosineResults) {
        partitionPassRates(corpus, results)
                .forEach((partition, passRate) -> assertTrue(
                        passRate >= corpus.minimumPartitionPassRate(),
                        () -> partition
                                + " passRate="
                                + passRate
                                + " required="
                                + corpus.minimumPartitionPassRate()
                                + " failures="
                                + corpus.testCases().stream()
                                        .filter(testCase ->
                                                testCase.partitions().contains(partition))
                                        .filter(testCase -> !results.get(testCase.id()))
                                        .map(testCase -> testCase.id() + "=" + cosineResults.get(testCase.id()))
                                        .toList()));
    }

    private static Map<String, Double> partitionPassRates(GoldenCorpus corpus, Map<String, Boolean> results) {
        Map<String, Double> passRates = new LinkedHashMap<>();
        for (String partition : corpus.requiredPartitions()) {
            List<TestCase> partitionCases = corpus.testCases().stream()
                    .filter(testCase -> testCase.partitions().contains(partition))
                    .toList();
            assertFalse(partitionCases.isEmpty(), "missing partition: " + partition);
            long passed = partitionCases.stream()
                    .filter(testCase -> results.get(testCase.id()))
                    .count();
            passRates.put(partition, (double) passed / partitionCases.size());
        }
        return passRates;
    }

    private static void assertCanonicalFixtures(GoldenCorpus corpus, Map<String, Boolean> results) {
        corpus.testCases().stream()
                .filter(TestCase::canonical)
                .forEach(testCase ->
                        assertTrue(results.get(testCase.id()), "canonical fixture failed: " + testCase.id()));
    }

    private static void assertNearestNeighborStability(GoldenCorpus corpus, Map<String, float[]> actualVectors) {
        Map<String, List<Double>> references = new LinkedHashMap<>();
        corpus.testCases().forEach(testCase -> references.put(testCase.id(), testCase.referenceVector()));
        for (TestCase testCase : corpus.testCases()) {
            List<String> expected = nearestNeighbors(testCase.id(), references, corpus.nearestNeighborK());
            List<String> actual = nearestNeighborsFloat(testCase.id(), actualVectors, corpus.nearestNeighborK());
            assertEquals(expected, actual, "nearest-neighbour ranking changed for " + testCase.id());
            if (!testCase.relevantIds().isEmpty()) {
                assertTrue(
                        expected.stream().anyMatch(testCase.relevantIds()::contains),
                        "reference retrieval misses labelled neighbours for " + testCase.id());
                assertTrue(
                        actual.stream().anyMatch(testCase.relevantIds()::contains),
                        "ONNX retrieval misses labelled neighbours for " + testCase.id());
            }
        }
    }

    private static void writeEvidenceReport(
            ObjectMapper objectMapper,
            GoldenCorpus corpus,
            Map<String, Double> cosineResults,
            Map<String, Boolean> toleranceResults,
            Map<String, float[]> actualVectors)
            throws Exception {
        Map<String, List<Double>> references = new LinkedHashMap<>();
        corpus.testCases().forEach(testCase -> references.put(testCase.id(), testCase.referenceVector()));
        Map<String, List<String>> referenceRankings = new LinkedHashMap<>();
        Map<String, List<String>> actualRankings = new LinkedHashMap<>();
        for (TestCase testCase : corpus.testCases()) {
            referenceRankings.put(
                    testCase.id(), nearestNeighbors(testCase.id(), references, corpus.nearestNeighborK()));
            actualRankings.put(
                    testCase.id(), nearestNeighborsFloat(testCase.id(), actualVectors, corpus.nearestNeighborK()));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("status", "passed");
        report.put("corpusVersion", corpus.version());
        report.put("modelId", corpus.modelId());
        report.put("modelRevision", corpus.revision());
        report.put("precision", corpus.precision());
        report.put("cosineThreshold", corpus.cosineThreshold());
        report.put("minimumPartitionPassRate", corpus.minimumPartitionPassRate());
        report.put("perExampleCosine", cosineResults);
        report.put("perExamplePassed", toleranceResults);
        report.put("partitionPassRates", partitionPassRates(corpus, toleranceResults));
        report.put("referenceNearestNeighbors", referenceRankings);
        report.put("actualNearestNeighbors", actualRankings);

        Path evidenceDirectory = Path.of(System.getProperty("amsha.onnx.evidence-dir", "target/onnx-evidence"));
        Files.createDirectories(evidenceDirectory);
        Path output = evidenceDirectory.resolve("golden-equivalence-report.json");
        Path temporary = evidenceDirectory.resolve("golden-equivalence-report.json.tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<String> nearestNeighbors(String queryId, Map<String, List<Double>> vectors, int limit) {
        List<Double> query = vectors.get(queryId);
        return vectors.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(queryId))
                .sorted((left, right) -> {
                    int cosineOrder = Double.compare(cosine(query, right.getValue()), cosine(query, left.getValue()));
                    return cosineOrder != 0 ? cosineOrder : left.getKey().compareTo(right.getKey());
                })
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<String> nearestNeighborsFloat(String queryId, Map<String, float[]> vectors, int limit) {
        float[] query = vectors.get(queryId);
        return vectors.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(queryId))
                .sorted((left, right) -> {
                    int cosineOrder = Double.compare(cosine(query, right.getValue()), cosine(query, left.getValue()));
                    return cosineOrder != 0 ? cosineOrder : left.getKey().compareTo(right.getKey());
                })
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static double cosine(float[] actual, List<Double> reference) {
        assertEquals(actual.length, reference.size());
        double dot = 0;
        double actualNorm = 0;
        double referenceNorm = 0;
        for (int index = 0; index < actual.length; index++) {
            assertTrue(Float.isFinite(actual[index]));
            dot += actual[index] * reference.get(index);
            actualNorm += actual[index] * actual[index];
            referenceNorm += reference.get(index) * reference.get(index);
        }
        return dot / Math.sqrt(actualNorm * referenceNorm);
    }

    private static double cosine(List<Double> left, List<Double> right) {
        assertEquals(left.size(), right.size());
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            dot += left.get(index) * right.get(index);
            leftNorm += left.get(index) * left.get(index);
            rightNorm += right.get(index) * right.get(index);
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static double cosine(float[] left, float[] right) {
        assertEquals(left.length, right.length);
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static Path modelRoot() {
        String configured = System.getProperty("amsha.onnx.model-root");
        if (configured != null && !configured.isBlank())
            return Path.of(configured).toAbsolutePath();
        return (Files.isDirectory(Path.of("data/models")) ? Path.of("data/models") : Path.of("../data/models"))
                .toAbsolutePath();
    }

    private static void requireEvidenceFile(Path file, boolean required, String description) {
        if (required) {
            assertTrue(Files.isRegularFile(file), description + " is required: " + file);
        } else {
            Assumptions.assumeTrue(Files.isRegularFile(file), description + " is not installed: " + file);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoldenCorpus(
            String version,
            String modelId,
            String revision,
            String precision,
            int vectorDimension,
            double cosineThreshold,
            double minimumPartitionPassRate,
            int nearestNeighborK,
            List<String> requiredPartitions,
            List<TestCase> testCases) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TestCase(
            String id,
            List<String> partitions,
            String input,
            boolean canonical,
            List<String> relevantIds,
            List<Double> referenceVector) {}
}
