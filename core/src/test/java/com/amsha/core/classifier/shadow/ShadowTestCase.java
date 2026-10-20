package com.amsha.core.classifier.shadow;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parsed test case from the shadow evaluation corpus.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShadowTestCase(
        String id,
        ShadowCorpusTask task,
        ShadowCorpusPartition partition,
        String expectedLabel,
        List<String> acceptableLabels,
        String riskCategory,
        String criteria,
        List<String> options,
        Map<String, Object> input,
        String notes) {

    public ShadowTestCase {
        acceptableLabels = acceptableLabels != null ? List.copyOf(acceptableLabels) : List.of();
        options = options != null ? List.copyOf(options) : List.of();
        input = input != null ? Map.copyOf(input) : Map.of();
    }

    public boolean isSecurityRelevant() {
        return "SECURITY".equalsIgnoreCase(riskCategory);
    }

    public boolean isAbstentionExpected() {
        return "NO_OPINION".equalsIgnoreCase(expectedLabel);
    }
}
