package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.amsha.core.util.TextPatternUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RecoveryProcessorTest {

    private RecoveryProcessor processor;

    @BeforeEach
    public void setUp() {
        processor = new RecoveryProcessor(new ObjectMapper(), new TextPatternUtils());
    }

    @Test
    public void isProseGenerationFailureDetectsMarker() {
        assertTrue(processor.isProseGenerationFailure("error {\"failed_generation\":\"text here\"}"));
        assertFalse(processor.isProseGenerationFailure("{\"other\":\"x\"}"));
        assertFalse(processor.isProseGenerationFailure(null));
        assertFalse(processor.isProseGenerationFailure("\"failed_generation\":\""));
    }

    @Test
    public void extractsPlainProseFromFailedGeneration() {
        String message = "Model failed: {\"failed_generation\":\"Hello world answer\"}";
        assertEquals("Hello world answer", processor.extractProseFromFailedGeneration(message));
        assertNull(processor.extractProseFromFailedGeneration("no marker"));
        assertNull(processor.extractProseFromFailedGeneration(null));
    }

    @Test
    public void extractsUnescapedProseAndHandlesJsonAnswerPayloads() {
        String escaped = "{\"failed_generation\":\"line one\\nline two\"}";
        assertEquals("line one\nline two", processor.extractProseFromFailedGeneration(escaped));

        String jsonAnswer = "{\"failed_generation\":\"{\\\"answer\\\": \\\"the real reply\\\"}\"}";
        assertEquals("the real reply", processor.extractProseFromFailedGeneration(jsonAnswer));

        // Braces but not JSON-wrapped (doesn't end with '}') -> returned verbatim
        assertEquals("{oops", processor.extractProseFromFailedGeneration("{\"failed_generation\":\"{oops\"}"));
    }

    @Test
    public void extractsPartialCodeFromTruncatedJsonAndUnescapes() {
        String truncated = "{\"language\": \"python\", \"code\": \"print(\\\"hi\\\")\\nprint('done')";
        assertEquals("print(\"hi\")\nprint('done')", processor.extractPartialCodeFromTruncatedJson(truncated));
        assertNull(processor.extractPartialCodeFromTruncatedJson("{\"x\": 1}"));
        assertNull(processor.extractPartialCodeFromTruncatedJson(null));
    }

    @Test
    public void detectsEmbeddedSandboxToolCalls() {
        assertTrue(processor.containsEmbeddedSandboxToolCall("call \"sandbox_exec\" now"));
        assertTrue(processor.containsEmbeddedSandboxToolCall("{\"code\": \"x\", \"language\": \"py\"}"));
        assertFalse(processor.containsEmbeddedSandboxToolCall("just prose"));
        assertFalse(processor.containsEmbeddedSandboxToolCall(null));
    }

    @Test
    public void flagsTruncatedOrPartialCodeShapes() {
        assertTrue(processor.looksLikeTruncatedOrPartialCode("output had u003c escapes"));
        assertFalse(processor.looksLikeTruncatedOrPartialCode("```java\ncode\n```"));
        assertTrue(processor.looksLikeTruncatedOrPartialCode("public class Foo {"));
        assertTrue(processor.looksLikeTruncatedOrPartialCode("partial thought without end"));
        assertFalse(processor.looksLikeTruncatedOrPartialCode("A complete sentence."));
        assertFalse(processor.looksLikeTruncatedOrPartialCode(""));
        assertFalse(processor.looksLikeTruncatedOrPartialCode(null));
    }

    @Test
    public void detectsUnexecutedCodePlans() {
        assertTrue(processor.proseIsUnexecutedCodePlan("here ```python\\ncode```"));
        assertTrue(processor.proseIsUnexecutedCodePlan("I would write: def main():"));
        assertFalse(processor.proseIsUnexecutedCodePlan("nothing code-like"));
        assertFalse(processor.proseIsUnexecutedCodePlan(null));
    }

    @Test
    public void extractLatestCodeBlockPrefersLastBlockAndNormalizesLanguage() {
        String text = "first:\n```python3\na = 1\n```\nthen:\n```sh\necho hi\n```";
        RecoveryProcessor.CodeBlock block = processor.extractCodeBlockFromText(text);
        assertEquals("bash", block.language());
        assertEquals("echo hi", block.code());

        assertEquals(
                "python",
                processor.extractCodeBlockFromText("```python3\nx = 2\n```").language());
        assertEquals(
                "node",
                processor.extractCodeBlockFromText("```js\nconsole.log(1)\n```").language());
    }

    @Test
    public void unsupportedFenceLanguageIsSkippedUnlessInferable() {
        // "cobol" is not executable here and inference on non-code text yields null
        assertNull(processor.extractCodeBlockFromText("```cobol\nMOVE A TO B.\n```"));
        assertNull(processor.extractCodeBlockFromText("plain prose only"));
        assertNull(processor.extractCodeBlockFromText(null));
    }

    @Test
    public void extractLatestFromHistoryScansBackwardsThroughAnswers() {
        ReasoningStep older = finishWithAnswer("older\n```python\np = 1\n```");
        ReasoningStep newer = finishWithAnswer("newer\n```go\nfmt.Println(1)\n```");

        RecoveryProcessor.CodeBlock block =
                processor.extractLatestCodeBlock(List.of(older, newer, finishWithAnswer("no code here")));

        assertEquals("go", block.language());
        assertNull(processor.extractLatestCodeBlock(List.of()));
        assertNull(processor.extractLatestCodeBlock(null));
    }

    private ReasoningStep finishWithAnswer(String answer) {
        return new ReasoningStep.FinishStep(
                "thought", Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, answer), 0, 0, false);
    }
}
