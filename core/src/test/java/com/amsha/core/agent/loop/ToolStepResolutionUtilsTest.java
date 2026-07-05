package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.ToolCapability;

import org.junit.jupiter.api.Test;

class ToolStepResolutionUtilsTest {

    @Test
    void templateReadDoesNotCountAsArtifactCompletion() {
        List<ReasoningStep> history = List.of(ReasoningStep.pending(
                        "read template",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of(
                                "code",
                                "cat /Users/srinivasanragothaman/.amsha/skills/skillsjars/algorithmic-art/templates/viewer.html"),
                        0,
                        0)
                .withObservation("<html>template content</html>", true));

        assertFalse(ToolStepResolutionUtils.hasRecentArtifactCompletion(history));
    }

    @Test
    void outputFolderOpenCountsAsArtifactCompletion() {
        List<ReasoningStep> history = List.of(ReasoningStep.pending(
                        "open generated file",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of("code", "open output/gentle-drift.html"),
                        0,
                        0)
                .withObservation("Opened browser preview: output/gentle-drift.html", true));

        assertTrue(ToolStepResolutionUtils.hasRecentArtifactCompletion(history));
    }

    @Test
    void fileUrlOpenCountsAsArtifactCompletion() {
        List<ReasoningStep> history = List.of(ReasoningStep.pending(
                        "open generated file",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of("code", "open output/particle-art.html"),
                        0,
                        0)
                .withObservation("Opened browser preview: file:///private/tmp/particle_art.html", true));

        assertTrue(ToolStepResolutionUtils.hasRecentArtifactCompletion(history));
    }
}
