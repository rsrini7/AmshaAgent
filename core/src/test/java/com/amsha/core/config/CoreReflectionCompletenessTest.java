package com.amsha.core.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.amsha.core.classifier.evaluation.DecisionEvidenceDiagnostics;
import com.amsha.core.classifier.evaluation.DecisionEvidenceRunAttribution;
import com.amsha.core.classifier.evaluation.DecisionEvidenceSummary;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.annotations.RegisterForReflection;

class CoreReflectionCompletenessTest {

    @Test
    void decisionEvidenceResponseTypesAreRegisteredByTheirOwningModule() {
        RegisterForReflection annotation = CoreReflectionConfig.class.getAnnotation(RegisterForReflection.class);
        assertNotNull(annotation, "CoreReflectionConfig must register the core native response graph");
        Set<Class<?>> targets = Set.of(annotation.targets());

        assertTrue(targets.contains(DecisionEvidenceSummary.class));
        assertTrue(targets.contains(DecisionEvidenceRunAttribution.class));
        assertTrue(targets.contains(DecisionEvidenceDiagnostics.class));
        assertTrue(targets.contains(DecisionEvidenceDiagnostics.ProviderUsage.class));
    }
}
