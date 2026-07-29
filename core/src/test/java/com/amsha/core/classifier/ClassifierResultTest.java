package com.amsha.core.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;

import org.junit.jupiter.api.Test;

class ClassifierResultTest {

    @Test
    void validatesOpinionConfidenceAndRuleIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new ClassifierResult.Opinion<>("coder", Float.NaN, "rule"));
        assertThrows(IllegalArgumentException.class, () -> new ClassifierResult.Opinion<>("coder", 1.01f, "rule"));
        assertThrows(IllegalArgumentException.class, () -> new ClassifierResult.Opinion<>("coder", 0.5f, " "));
    }

    @Test
    void preservesExplicitNoOpinionReason() {
        var result = new ClassifierResult.NoOpinion<String>(NoOpinionReason.CIRCUIT_OPEN);

        assertEquals(NoOpinionReason.CIRCUIT_OPEN, result.reason());
    }
}
