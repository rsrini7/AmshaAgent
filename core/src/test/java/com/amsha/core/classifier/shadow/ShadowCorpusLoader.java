package com.amsha.core.classifier.shadow;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads and validates test cases from the shadow evaluation corpus JSON.
 * Rejects duplicate test case identifiers and enforces strict duplicate JSON field rejection.
 */
public final class ShadowCorpusLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ShadowCorpusLoader() {}

    /**
     * Loads, parses, and validates the shadow test cases from the given stream.
     *
     * @param stream the input stream containing JSON corpus
     * @return unmodifiable list of validated test cases
     * @throws IOException on parsing error
     * @throws IllegalArgumentException on duplicate test case ID or invalid payload
     */
    public static List<ShadowTestCase> load(InputStream stream) throws IOException {
        Objects.requireNonNull(stream, "stream");
        Map<String, Object> root = MAPPER.readValue(stream, new TypeReference<>() {});
        Object casesObj = root.get("cases");
        if (!(casesObj instanceof List<?>)) {
            casesObj = root.get("testCases");
        }
        if (!(casesObj instanceof List<?> rawList)) {
            throw new IllegalArgumentException("Corpus JSON must contain a 'cases' array");
        }

        List<ShadowTestCase> result = new ArrayList<>(rawList.size());
        Set<String> seenIds = new HashSet<>();

        for (Object item : rawList) {
            ShadowTestCase tc = MAPPER.convertValue(item, ShadowTestCase.class);
            if (tc.id() == null || tc.id().isBlank()) {
                throw new IllegalArgumentException("Test case ID must not be null or blank");
            }
            if (tc.task() == null
                    || tc.partition() == null
                    || tc.expectedLabel() == null
                    || tc.expectedLabel().isBlank()) {
                throw new IllegalArgumentException("Corpus case is missing required evaluation fields");
            }
            if (!seenIds.add(tc.id())) {
                throw new IllegalArgumentException("Duplicate test case identifier detected in corpus: " + tc.id());
            }
            result.add(tc);
        }

        return List.copyOf(result);
    }
}
