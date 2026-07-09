package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;

import org.junit.jupiter.api.Test;

class LoopStuckDetectorTest {

    private final LoopStuckDetector detector = new LoopStuckDetector();

    @Test
    void emptyHistory_returnsEmpty() {
        Optional<String> hint = detector.detectStuckHint(List.of(), "skill:algorithmic-art");
        assertFalse(hint.isPresent(), "Empty history should not produce a hint");
    }

    @Test
    void singleStep_returnsEmpty() {
        List<ReasoningStep> history = List.of(toolCall("host_exec", Map.of("code", "cat /foo")));
        Optional<String> hint = detector.detectStuckHint(history, "skill:algorithmic-art");
        assertFalse(hint.isPresent(), "Single step should not produce a hint");
    }

    @Test
    void twoIdenticalReadCommands_returnsHintMentioningPath() {
        List<ReasoningStep> history = List.of(
                toolCall(
                        "host_exec",
                        Map.of("code", "cat /Users/srini/.amsha/skills/algorithmic-art/templates/viewer.html")),
                toolCall(
                        "host_exec",
                        Map.of("code", "cat /Users/srini/.amsha/skills/algorithmic-art/templates/viewer.html")));

        Optional<String> hint = detector.detectStuckHint(history, "skill:algorithmic-art");

        assertTrue(hint.isPresent(), "Repeated identical read commands should produce a hint");
        String text = hint.get();
        assertTrue(text.contains("host_exec"), "Hint should mention the tool");
        assertTrue(text.contains("viewer.html"), "Hint should mention the repeated path");
        assertTrue(text.contains("STOP repeating"), "Hint should instruct the model to stop");
        assertTrue(text.contains("2 times"), "Hint should report the repeat count");
    }

    @Test
    void twoIdenticalWriteCommands_returnsHint() {
        List<ReasoningStep> history = List.of(
                toolCall("host_exec", Map.of("code", "python generate.py")),
                toolCall("host_exec", Map.of("code", "python generate.py")));

        Optional<String> hint = detector.detectStuckHint(history, "skill:algorithmic-art");

        assertTrue(hint.isPresent(), "Repeated identical commands should produce a hint");
        String text = hint.get();
        assertTrue(text.contains("python generate.py"), "Hint should mention the repeated command");
    }

    @Test
    void differentCommands_noHint() {
        List<ReasoningStep> history = List.of(
                toolCall("host_exec", Map.of("code", "cat /foo")), toolCall("host_exec", Map.of("code", "cat /bar")));

        Optional<String> hint = detector.detectStuckHint(history, "skill:algorithmic-art");
        assertFalse(hint.isPresent(), "Different commands should not produce a hint");
    }

    @Test
    void threeConsecutiveReadOnlyCommands_returnsReadOnlyLoopHint() {
        List<ReasoningStep> history = List.of(
                toolCall("host_exec", Map.of("code", "ls /foo")),
                toolCall("host_exec", Map.of("code", "cat /foo/a")),
                toolCall("host_exec", Map.of("code", "grep x /foo/b")));

        Optional<String> hint = detector.detectStuckHint(history, "skill:algorithmic-art");

        assertTrue(hint.isPresent(), "Three read-only commands should trigger the read-only loop hint");
        String text = hint.get();
        assertTrue(text.contains("STOP inspecting"), "Hint should tell the model to stop inspecting");
        assertTrue(text.contains("3"), "Hint should mention the threshold");
    }

    @Test
    void readOnlyLoopWithWriteInBetween_noHint() {
        List<ReasoningStep> history = List.of(
                toolCall("host_exec", Map.of("code", "cat /foo")),
                toolCall("host_exec", Map.of("code", "echo done > /foo/out")),
                toolCall("host_exec", Map.of("code", "cat /foo")));

        Optional<String> hint = detector.detectStuckHint(history, "skill:algorithmic-art");
        assertFalse(hint.isPresent(), "A write in between should break the read-only loop detection");
    }

    @Test
    void finishStepIncluded_noHint() {
        List<ReasoningStep> history = List.of(
                toolCall("host_exec", Map.of("code", "cat /foo")),
                toolCall("host_exec", Map.of("code", "cat /foo")),
                finishStep("Done"));

        Optional<String> hint = detector.detectStuckHint(history, "skill:algorithmic-art");
        assertFalse(hint.isPresent(), "History containing a FINISH should not be stuck");
    }

    @Test
    void identicalRepetitionTakesPrecedenceOverReadOnlyLoop() {
        // 3 read-only commands, but last 2 are identical -> identical repetition hint wins.
        List<ReasoningStep> history = List.of(
                toolCall("host_exec", Map.of("code", "ls /foo")),
                toolCall("host_exec", Map.of("code", "cat /foo/a")),
                toolCall("host_exec", Map.of("code", "cat /foo/a")));

        Optional<String> hint = detector.detectStuckHint(history, "skill:algorithmic-art");

        assertTrue(hint.isPresent());
        assertTrue(hint.get().contains("STOP repeating"), "Identical repetition should take precedence");
    }

    @Test
    void bashWrapperReadCommand_detectedAsRead() {
        List<ReasoningStep> history = List.of(
                toolCall("host_exec", Map.of("code", "bash -c 'cat /foo'")),
                toolCall("host_exec", Map.of("code", "bash -c 'cat /foo'")));

        Optional<String> hint = detector.detectStuckHint(history, "skill:algorithmic-art");

        assertTrue(hint.isPresent(), "Wrapped read commands should still be detected");
        assertTrue(hint.get().contains("cat /foo"), "Hint should mention the inner command");
    }

    @Test
    void nonStringActionInputKeys_summarizedSafely() {
        List<ReasoningStep> history = List.of(
                toolCall("some_tool", Map.of("enabled", true, "count", 3)),
                toolCall("some_tool", Map.of("enabled", true, "count", 3)));

        Optional<String> hint = detector.detectStuckHint(history, "coordinator");

        assertTrue(hint.isPresent(), "Identical non-string parameters should trigger a hint");
        assertTrue(hint.get().contains("some_tool"), "Hint should mention the tool");
    }

    private static ReasoningStep toolCall(String toolName, Map<String, Object> input) {
        return ReasoningStep.fastPath("thought", toolName, input);
    }

    private static ReasoningStep finishStep(String answer) {
        return new ReasoningStep.FinishStep(
                "Finished", Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, answer), 0, 0, false);
    }
}
