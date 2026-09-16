package com.amsha.llm.providers.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgyCliExecutor}.
 *
 * <h3>How the subprocess path is driven</h3>
 *
 * {@link AgyCliExecutor#execute} spawns the real {@code agy} binary via {@link ProcessBuilder}, so it cannot be
 * mocked without replacing the JDK. Instead each test writes a tiny POSIX shell script into a temp directory and
 * passes it as the {@code agyExe} argument — the executor only ever calls {@code agyExe.toString()}, so a script is
 * indistinguishable from the real binary while remaining fully deterministic (canned stdout, chosen exit code,
 * chosen stderr, or a deliberate hang).
 *
 * <h3>Environment-locked branches</h3>
 *
 * {@link AgyCliExecutor#resolveCliPath()} reads {@code ANTIGRAVITY_AGENTAPI_EXE} and {@code PATH} through
 * {@link System#getenv}, which a test cannot set. Two assertions below are therefore guarded with
 * {@link Assumptions} (they report as skipped, not passed) rather than silently branching. The two lines that stay
 * unreachable as a result are documented in the coverage report.
 */
class AgyCliExecutorTest {

    private static final String DEFAULT_MODEL = "Gemini 3.7 Flash (Low)";
    private static final String JSON_DELIMITER = "AMSHA_JSON_EOF";

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-agy-cli-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) return;
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException _) {
                    // best-effort cleanup
                }
            });
        }
    }

    // ------------------------------------------------------------------ fake CLI helpers

    /** Writes an executable POSIX shell script and returns its path. */
    private Path cliScript(String body) throws IOException {
        Path exe = tempDir.resolve("agy-" + UUID.randomUUID() + ".sh");
        Files.writeString(exe, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        assertTrue(exe.toFile().setExecutable(true), "could not mark the fake CLI executable");
        return exe;
    }

    /** A CLI that prints {@code json} on stdout and exits 0. The quoted heredoc keeps the JSON verbatim. */
    private Path cliEmitting(String json) throws IOException {
        return cliScript("cat <<'" + JSON_DELIMITER + "'\n" + json + "\n" + JSON_DELIMITER);
    }

    /** A CLI that writes {@code stderr} (when non-blank) and exits with {@code exitCode}. */
    private Path cliFailing(int exitCode, String stderr) throws IOException {
        StringBuilder body = new StringBuilder();
        if (!stderr.isBlank()) {
            body.append("printf '%s' '").append(stderr).append("' >&2\n");
        }
        body.append("exit ").append(exitCode);
        return cliScript(body.toString());
    }

    private static ChatResponse run(Path exe, String model, List<ChatMessage> messages, Duration timeout) {
        return AgyCliExecutor.execute(exe, model, messages, timeout, new ObjectMapper());
    }

    private static ChatResponse run(
            Path exe, List<ChatMessage> messages, List<ToolSpecification> tools, Duration timeout) {
        return AgyCliExecutor.execute(exe, "gemini-3.7-flash", messages, tools, timeout, new ObjectMapper());
    }

    private static List<ChatMessage> oneUserMessage() {
        return List.of(UserMessage.from("hello"));
    }

    private static boolean noAgyOnPath() {
        String path = System.getenv("PATH");
        if (path == null) return true;
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, "agy"))) return false;
        }
        return true;
    }

    // --------------------------------------------------------------- resolveCliPath()

    @Test
    void resolveCliPathFallsBackToThePathScanAndReturnsNullWhenNothingMatches() throws IOException {
        Assumptions.assumeTrue(
                System.getenv("ANTIGRAVITY_AGENTAPI_EXE") == null, "a custom agy executable is configured");
        Assumptions.assumeTrue(noAgyOnPath(), "an agy binary is present on PATH");

        String realHome = System.getProperty("user.home");
        Path fakeHome = Files.createTempDirectory("amsha-fake-home-");
        try {
            // Point user.home at a directory with no .local/bin/agy so the PATH scan is reached.
            System.setProperty("user.home", fakeHome.toString());
            assertNull(AgyCliExecutor.resolveCliPath());
            assertFalse(AgyCliExecutor.isAvailable());
        } finally {
            if (realHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", realHome);
            }
        }
    }

    @Test
    void resolveCliPathPrefersAnExecutableUnderTheUserHome() throws IOException {
        String realHome = System.getProperty("user.home");
        Path fakeHome = Files.createTempDirectory("amsha-fake-home-with-agy-");
        try {
            Path bin = Files.createDirectories(fakeHome.resolve(".local").resolve("bin"));
            Path agy = bin.resolve("agy");
            Files.writeString(agy, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
            assertTrue(agy.toFile().setExecutable(true));
            System.setProperty("user.home", fakeHome.toString());

            assertEquals(agy, AgyCliExecutor.resolveCliPath());
            assertTrue(AgyCliExecutor.isAvailable());
        } finally {
            if (realHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", realHome);
            }
        }
    }

    // -------------------------------------------------------------- resolveCliModel()

    @Test
    void resolveCliModelDefaultsForNullOrBlank() {
        assertEquals(DEFAULT_MODEL, AgyCliExecutor.resolveCliModel(null));
        assertEquals(DEFAULT_MODEL, AgyCliExecutor.resolveCliModel(""));
        assertEquals(DEFAULT_MODEL, AgyCliExecutor.resolveCliModel("   "));
    }

    @Test
    void resolveCliModelMapsEveryKnownFamily() {
        assertEquals(DEFAULT_MODEL, AgyCliExecutor.resolveCliModel("gemini-3.7-flash"));
        assertEquals(DEFAULT_MODEL, AgyCliExecutor.resolveCliModel("flash-latest"));
        assertEquals(DEFAULT_MODEL, AgyCliExecutor.resolveCliModel("flash-lite"));
        assertEquals("Gemini 3.6 Flash (Low)", AgyCliExecutor.resolveCliModel("gemini-3.6-flash"));
        assertEquals("Gemini 3.5 Flash (Low)", AgyCliExecutor.resolveCliModel("gemini-3.5-flash"));
        assertEquals("Gemini 3.1 Pro (Low)", AgyCliExecutor.resolveCliModel("gemini-3.1-pro"));
        assertEquals("Gemini 3.1 Pro (Low)", AgyCliExecutor.resolveCliModel("pro"));
        assertEquals("Claude Sonnet 4.6 (Thinking)", AgyCliExecutor.resolveCliModel("claude-sonnet"));
        assertEquals("Claude Sonnet 4.6 (Thinking)", AgyCliExecutor.resolveCliModel("SONNET"));
        assertEquals("GPT-OSS 120B (Medium)", AgyCliExecutor.resolveCliModel("gpt-4o"));
        assertEquals("GPT-OSS 120B (Medium)", AgyCliExecutor.resolveCliModel("oss-120b"));
    }

    @Test
    void resolveCliModelPassesUnknownNamesThrough() {
        assertEquals("some-unknown-model", AgyCliExecutor.resolveCliModel("some-unknown-model"));
    }

    // -------------------------------------------------------------- buildCliPrompt()

    @Test
    void buildCliPromptReturnsEmptyForNullOrEmptyMessages() {
        assertEquals("", AgyCliExecutor.buildCliPrompt(null));
        assertEquals("", AgyCliExecutor.buildCliPrompt(List.of()));
    }

    @Test
    void buildCliPromptRendersEveryMessageKindAndSkipsBlankAssistantTurns() {
        String prompt = AgyCliExecutor.buildCliPrompt(List.of(
                SystemMessage.from("be terse"),
                UserMessage.from("hello"),
                AiMessage.from("   "),
                ToolExecutionResultMessage.from("call-1", "read_file", "file contents")));

        assertTrue(prompt.startsWith("System Instructions:\nbe terse"), prompt);
        assertTrue(prompt.contains("User: hello"), prompt);
        assertFalse(prompt.contains("Assistant:"), "a blank assistant turn must be skipped: " + prompt);
        assertTrue(prompt.contains("Tool [read_file] Output: file contents"), prompt);
    }

    @Test
    void buildCliPromptIncludesANonBlankAssistantTurn() {
        String prompt = AgyCliExecutor.buildCliPrompt(List.of(AiMessage.from("earlier answer")));
        assertTrue(prompt.contains("Assistant: earlier answer"), prompt);
    }

    @Test
    void buildCliPromptListsToolsWithAndWithoutDescriptions() {
        List<ToolSpecification> tools = List.of(
                ToolSpecification.builder()
                        .name("read_file")
                        .description("Reads a file")
                        .build(),
                ToolSpecification.builder().name("no_description").build());

        String prompt = AgyCliExecutor.buildCliPrompt(oneUserMessage(), tools, new ObjectMapper());

        assertTrue(prompt.contains("--- Available Tools ---"), prompt);
        assertTrue(prompt.contains("- Tool: read_file (Reads a file)"), prompt);
        assertTrue(prompt.contains("- Tool: no_description"), prompt);
        assertFalse(prompt.contains("(null)"), prompt);
        assertTrue(prompt.contains("{\"tool\": \"<tool_name>\", \"parameters\": { ... }}"), prompt);
    }

    @Test
    void buildCliPromptIgnoresAnEmptyToolList() {
        String prompt = AgyCliExecutor.buildCliPrompt(oneUserMessage(), List.of(), new ObjectMapper());
        assertFalse(prompt.contains("--- Available Tools ---"), prompt);
        assertEquals("User: hello", prompt);
    }

    // --------------------------------------------------------------------- execute()

    @Test
    void executeReturnsTheResponseTextAndTokenUsage() throws IOException {
        Path exe = cliEmitting("{\"status\":\"OK\",\"response\":\"hello from cli\","
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":7}}");

        ChatResponse response = run(exe, "gemini-3.7-flash", oneUserMessage(), Duration.ofSeconds(30));

        assertEquals("hello from cli", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals(5, response.tokenUsage().inputTokenCount());
        assertEquals(7, response.tokenUsage().outputTokenCount());
    }

    @Test
    void executeOmitsTokenUsageWhenBothCountsAreZero() throws IOException {
        Path exe = cliEmitting("{\"status\":\"OK\",\"response\":\"no usage\",\"usage\":{}}");

        ChatResponse response = run(exe, "gemini-3.7-flash", oneUserMessage(), Duration.ofSeconds(30));

        assertEquals("no usage", response.aiMessage().text());
        assertNull(response.tokenUsage(), "a zero/absent usage block must not produce a TokenUsage");
    }

    @Test
    void executeWorksWithoutAnExplicitObjectMapper() throws IOException {
        Path exe = cliEmitting("{\"status\":\"OK\",\"response\":\"mapper defaulted\"}");

        ChatResponse response =
                AgyCliExecutor.execute(exe, "gemini-3.7-flash", oneUserMessage(), null, Duration.ofSeconds(30), null);

        assertEquals("mapper defaulted", response.aiMessage().text());
    }

    @Test
    void executeSurfacesAnErrorStatusPayload() throws IOException {
        Path exe = cliEmitting("{\"status\":\"ERROR\",\"error\":\"quota exhausted\"}");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> run(exe, "gemini-3.7-flash", oneUserMessage(), Duration.ofSeconds(30)));
        assertTrue(thrown.getMessage().contains("quota exhausted"), thrown.getMessage());
    }

    @Test
    void executeReportsANonZeroExitCodeWithStderr() throws IOException {
        Path exe = cliFailing(3, "boom on stderr");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> run(exe, "gemini-3.7-flash", oneUserMessage(), Duration.ofSeconds(30)));
        assertTrue(thrown.getMessage().contains("exited with code 3"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("boom on stderr"), thrown.getMessage());
    }

    @Test
    void executeReportsANonZeroExitCodeWithoutStderr() throws IOException {
        Path exe = cliFailing(4, "   ");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> run(exe, "gemini-3.7-flash", oneUserMessage(), Duration.ofSeconds(30)));
        assertTrue(thrown.getMessage().contains("exited with code 4"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains(": "), "blank stderr must not add a suffix: " + thrown.getMessage());
    }

    @Test
    void executeKillsTheProcessOnTimeout() throws IOException {
        Path exe = cliScript("sleep 30");

        // Duration.ZERO.toSeconds() == 0, so waitFor returns immediately without blocking the test.
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> run(exe, "gemini-3.7-flash", oneUserMessage(), Duration.ZERO));
        assertTrue(thrown.getMessage().contains("timed out after 0 seconds"), thrown.getMessage());
    }

    @Test
    void executeWrapsAnUnstartableProcess() {
        Path missing = tempDir.resolve("definitely-not-here");

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> run(missing, "gemini-3.7-flash", oneUserMessage(), Duration.ofSeconds(30)));
        assertTrue(thrown.getMessage().contains("Failed to execute Antigravity CLI request"), thrown.getMessage());
        assertNotNull(thrown.getCause());
    }

    @Test
    void executeWrapsMalformedJson() throws IOException {
        Path exe = cliEmitting("this is not json at all");

        RuntimeException thrown = assertThrows(
                RuntimeException.class, () -> run(exe, "gemini-3.7-flash", oneUserMessage(), Duration.ofSeconds(30)));
        assertTrue(thrown.getMessage().contains("Failed to execute Antigravity CLI request"), thrown.getMessage());
    }

    @Test
    void executeReturnsAToolExecutionRequestWhenTheModelCallsATool() throws IOException {
        Path exe = cliEmitting("{\"status\":\"OK\",\"response\":"
                + "\"{\\\"tool\\\": \\\"read_file\\\", \\\"parameters\\\": {\\\"path\\\": \\\"a.txt\\\"}}\"}");
        List<ToolSpecification> tools =
                List.of(ToolSpecification.builder().name("read_file").build());

        ChatResponse response = run(exe, oneUserMessage(), tools, Duration.ofSeconds(30));

        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        assertEquals(
                "read_file", response.aiMessage().toolExecutionRequests().get(0).name());
    }

    @Test
    void executeTreatsPlainTextAsAnAnswerEvenWhenToolsAreDeclared() throws IOException {
        Path exe = cliEmitting("{\"status\":\"OK\",\"response\":\"just prose, no tool call\"}");
        List<ToolSpecification> tools =
                List.of(ToolSpecification.builder().name("read_file").build());

        ChatResponse response = run(exe, oneUserMessage(), tools, Duration.ofSeconds(30));

        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals("just prose, no tool call", response.aiMessage().text());
    }

    @Test
    void executeNeverParsesAToolCallWhenNoToolsAreDeclared() throws IOException {
        Path exe = cliEmitting("{\"status\":\"OK\",\"response\":\"{\\\"tool\\\": \\\"read_file\\\"}\"}");

        ChatResponse response = run(exe, "gemini-3.7-flash", oneUserMessage(), Duration.ofSeconds(30));

        assertEquals(FinishReason.STOP, response.finishReason(), "no tools declared means no tool call");
    }

    // ---------------------------------------------------------------- parseToolCall()

    @Test
    void parseToolCallRejectsNullOrBlankText() {
        ObjectMapper mapper = new ObjectMapper();
        assertNull(AgyCliExecutor.parseToolCall(null, mapper));
        assertNull(AgyCliExecutor.parseToolCall("   ", mapper));
    }

    @Test
    void parseToolCallReadsTheToolKey() {
        ToolExecutionRequest request = AgyCliExecutor.parseToolCall(
                "{\"tool\": \"read_file\", \"parameters\": {\"path\": \"a.txt\"}}", new ObjectMapper());

        assertNotNull(request);
        assertEquals("read_file", request.name());
        assertTrue(request.arguments().contains("a.txt"));
    }

    @Test
    void parseToolCallFallsBackToTheNameKey() {
        ToolExecutionRequest request = AgyCliExecutor.parseToolCall(
                "{\"name\": \"write_file\", \"arguments\": {\"path\": \"b.txt\"}}", new ObjectMapper());

        assertNotNull(request);
        assertEquals("write_file", request.name());
        assertTrue(request.arguments().contains("b.txt"));
    }

    @Test
    void parseToolCallAcceptsATextualArgumentsNode() {
        ToolExecutionRequest request = AgyCliExecutor.parseToolCall(
                "{\"tool\": \"echo\", \"arguments\": \"raw-argument-string\"}", new ObjectMapper());

        assertNotNull(request);
        assertEquals("echo", request.name());
        assertEquals("raw-argument-string", request.arguments());
    }

    @Test
    void parseToolCallUsesTheWholeObjectWhenNoArgumentsKeyIsPresent() {
        ToolExecutionRequest request =
                AgyCliExecutor.parseToolCall("{\"tool\": \"ping\", \"retries\": 2}", new ObjectMapper());

        assertNotNull(request);
        assertEquals("ping", request.name());
        assertTrue(request.arguments().contains("retries"));
    }

    @Test
    void parseToolCallUnwrapsAFencedCodeBlock() {
        ToolExecutionRequest request = AgyCliExecutor.parseToolCall(
                "Here you go:\n```json\n{\"tool\": \"read_file\", \"parameters\": {}}\n```", new ObjectMapper());

        assertNotNull(request);
        assertEquals("read_file", request.name());
    }

    @Test
    void parseToolCallReturnsNullWhenNoJsonObjectIsPresent() {
        assertNull(AgyCliExecutor.parseToolCall("no braces here", new ObjectMapper()));
        assertNull(AgyCliExecutor.parseToolCall("{\"unterminated\": ", new ObjectMapper()));
    }

    @Test
    void parseToolCallReturnsNullWhenTheObjectCarriesNoToolName() {
        assertNull(AgyCliExecutor.parseToolCall("{\"parameters\": {\"path\": \"a.txt\"}}", new ObjectMapper()));
        assertNull(AgyCliExecutor.parseToolCall("{\"tool\": \"   \"}", new ObjectMapper()));
    }

    @Test
    void parseToolCallSwallowsMalformedJson() {
        assertNull(AgyCliExecutor.parseToolCall("{not: valid: json}", new ObjectMapper()));
    }
}
