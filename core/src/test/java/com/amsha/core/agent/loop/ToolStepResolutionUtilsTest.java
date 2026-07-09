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

    @Test
    void midSentenceOpenSourceIsNotArtifactCompletionSignal() {
        // Regression: serper_web_search results containing "open source" prose used to falsely trigger
        // the artifact-completion fast path. After tightening the matcher, only line-starting shell
        // commands like "open <path>" should count.
        ReasoningStep step = ReasoningStep.pending(
                        "web search",
                        ToolCapability.WEB_SEARCH_SERPER.toolName(),
                        Map.of("query", "latest LTS version of foo"),
                        0,
                        0)
                .withObservation(
                        "1. Project Foo is an open source initiative backed by the community.\n"
                                + "2. Open source licenses compared.\n"
                                + "Latest LTS version is 42.",
                        true);

        assertFalse(ToolStepResolutionUtils.isArtifactCompletionSignal(step));
    }

    @Test
    void lineStartingOpenOutputIsArtifactCompletionSignal() {
        ReasoningStep step = ReasoningStep.pending(
                        "open generated file",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of("code", "open output/particle-art.html"),
                        0,
                        0)
                .withObservation("Opened browser preview: output/particle-art.html", true);

        assertTrue(ToolStepResolutionUtils.isArtifactCompletionSignal(step));
    }

    @Test
    void lineStartingStartOutputBackslashIsArtifactCompletionSignal() {
        // Windows-style shell command at the start of a line.
        ReasoningStep step = ReasoningStep.pending(
                        "open generated file",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of("code", "start output\\particle-art.html"),
                        0,
                        0)
                .withObservation("Opened browser preview: output\\particle-art.html", true);

        assertTrue(ToolStepResolutionUtils.isArtifactCompletionSignal(step));
    }

    @Test
    void openAfterNewlineIsArtifactCompletionSignal() {
        // A "open <path>" command appearing after a newline (e.g. multi-line script output) should
        // still match, since it is a real shell command, not prose.
        ReasoningStep step = ReasoningStep.pending(
                        "run script",
                        ToolCapability.HOST_EXEC.toolName(),
                        Map.of("code", "echo done\nopen output/foo.html"),
                        0,
                        0)
                .withObservation("done\nopened default browser at output/foo.html", true);

        assertTrue(ToolStepResolutionUtils.isArtifactCompletionSignal(step));
    }
}
