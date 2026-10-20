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

import com.amsha.common.config.ConfigKeys;
import com.amsha.llm.api.ModelDefaults;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FoundryCliExecutor}.
 *
 * <h3>How the subprocess path is driven</h3>
 *
 * {@link FoundryCliExecutor#execute} spawns the real {@code foundry} binary via {@link ProcessBuilder}, so it cannot
 * be mocked without replacing the JDK. Each test writes a tiny POSIX shell script into a temp directory and passes it
 * as the {@code foundryExe} argument — the executor only ever calls {@code foundryExe.toString()}, so a script is
 * indistinguishable from the real binary while remaining fully deterministic (canned stdout, chosen exit code,
 * chosen stderr, or a deliberate hang). A quoted heredoc keeps the JSON payload verbatim.
 *
 * <h3>Environment-locked branches</h3>
 *
 * {@link FoundryCliExecutor#resolveCliPath()} probes {@code AMSHA_FOUNDRY_EXE}, the absolute path
 * {@code /opt/homebrew/bin/foundry}, {@code ~/.local/bin/foundry} and {@code PATH} — all of which are properties of
 * the machine. Assertions that depend on them are guarded with {@link Assumptions} so they report as skipped rather
 * than silently branching. The consequence is that some lines are unreachable from a test; those are listed in the
 * coverage report rather than reached by reflection.
 */
class FoundryCliExecutorTest {

    private static final String JSON_DELIMITER = "AMSHA_JSON_EOF";
    private static final String HOMEBREW_FOUNDRY = "/opt/homebrew/bin/foundry";

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-foundry-cli-test-");
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
        Path exe = tempDir.resolve("foundry-" + UUID.randomUUID() + ".sh");
        Files.writeString(exe, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        assertTrue(exe.toFile().setExecutable(true), "could not mark the fake CLI executable");
        return exe;
    }

    /** A CLI that prints {@code payload} on stdout and exits 0. The quoted heredoc keeps it verbatim. */
    private Path cliEmitting(String payload) throws IOException {
        return cliScript("cat <<'" + JSON_DELIMITER + "'\n" + payload + "\n" + JSON_DELIMITER);
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
        return FoundryCliExecutor.execute(exe, model, messages, timeout, new ObjectMapper());
    }

    private static ChatResponse run(
            Path exe, List<ChatMessage> messages, List<ToolSpecification> tools, Duration timeout) {
        return FoundryCliExecutor.execute(exe, "qwen3-14b", messages, tools, timeout, new ObjectMapper());
    }

    private static List<ChatMessage> oneUserMessage() {
        return List.of(UserMessage.from("hello"));
    }

    private static boolean noFoundryOnPath() {
        String path = System.getenv("PATH");
        if (path == null) return true;
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, "foundry"))) return false;
        }
        return true;
    }

    private static void withUserHome(String value, Runnable body) {
        String realHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", value);
            body.run();
        } finally {
            if (realHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", realHome);
            }
        }
    }

    // --------------------------------------------------------------- resolveCliPath()

    @Test
    void isAvailableReflectsWhetherAnyFoundryBinaryResolves() {
        Assumptions.assumeTrue(
                System.getenv(ConfigKeys.FOUNDRY_EXE) == null, "a custom foundry executable is configured");
        // A real user.home means the /opt/homebrew/bin/foundry probe runs; either it or the PATH scan decides.
        assertEquals(FoundryCliExecutor.resolveCliPath() != null, FoundryCliExecutor.isAvailable());
    }

    @Test
    void resolveCliPathPrefersTheHomebrewInstall() {
        Assumptions.assumeTrue(
                System.getenv(ConfigKeys.FOUNDRY_EXE) == null, "a custom foundry executable is configured");
        Assumptions.assumeTrue(
                Files.isExecutable(Path.of(HOMEBREW_FOUNDRY)), "no homebrew foundry install on this machine");

        assertEquals(Path.of(HOMEBREW_FOUNDRY), FoundryCliExecutor.resolveCliPath());
    }

    @Test
    void resolveCliPathSkipsTheUserHomeBlockWhenUserHomeIsBlank() {
        Assumptions.assumeTrue(
                System.getenv(ConfigKeys.FOUNDRY_EXE) == null, "a custom foundry executable is configured");
        Assumptions.assumeTrue(noFoundryOnPath(), "a foundry binary is present on PATH");

        // A blank user.home skips the /opt/homebrew and ~/.local probes, so the PATH scan runs to completion.
        withUserHome("", () -> assertNull(FoundryCliExecutor.resolveCliPath()));
    }

    @Test
    void resolveCliPathFindsFoundryUnderTheUserHomeLocalBin() throws IOException {
        Assumptions.assumeTrue(
                System.getenv(ConfigKeys.FOUNDRY_EXE) == null, "a custom foundry executable is configured");
        Assumptions.assumeTrue(
                !Files.isExecutable(Path.of(HOMEBREW_FOUNDRY)), "homebrew foundry short-circuits this probe");

        Path fakeHome = Files.createTempDirectory("amsha-fake-foundry-home-");
        Path bin = Files.createDirectories(fakeHome.resolve(".local").resolve("bin"));
        Path foundry = bin.resolve("foundry");
        Files.writeString(foundry, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        assertTrue(foundry.toFile().setExecutable(true));

        withUserHome(fakeHome.toString(), () -> assertEquals(foundry, FoundryCliExecutor.resolveCliPath()));
    }

    // -------------------------------------------------------------- buildCliPrompt()

    @Test
    void buildCliPromptReturnsEmptyForNullOrEmptyMessages() {
        assertEquals("", FoundryCliExecutor.buildCliPrompt(null));
        assertEquals("", FoundryCliExecutor.buildCliPrompt(List.of()));
    }

    @Test
    void buildCliPromptRendersEveryMessageKind() {
        String prompt = FoundryCliExecutor.buildCliPrompt(List.of(
                SystemMessage.from("be terse"),
                UserMessage.from("hello"),
                AiMessage.from("earlier answer"),
                ToolExecutionResultMessage.from("call-1", "read_file", "file contents")));

        assertTrue(prompt.startsWith("System Instructions:\nbe terse"), prompt);
        assertTrue(prompt.contains("User: hello"), prompt);
        assertTrue(prompt.contains("Assistant: earlier answer"), prompt);
        assertTrue(prompt.contains("Tool [read_file] Output: file contents"), prompt);
    }

    @Test
    void buildCliPromptSkipsABlankAssistantTextButKeepsItsToolCalls() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("read_file")
                .arguments("{\"path\":\"a.txt\"}")
                .build();
        String prompt = FoundryCliExecutor.buildCliPrompt(List.of(AiMessage.from(List.of(request))));

        assertFalse(prompt.contains("Assistant: "), "blank assistant text must not be rendered: " + prompt);
        assertTrue(prompt.contains("Assistant Tool Call: read_file with args: {\"path\":\"a.txt\"}"), prompt);
    }

    @Test
    void buildCliPromptRendersEmptyArgumentsAsAnEmptyObject() {
        ToolExecutionRequest request =
                ToolExecutionRequest.builder().name("ping").arguments(null).build();
        String prompt = FoundryCliExecutor.buildCliPrompt(List.of(AiMessage.from(List.of(request))));

        assertTrue(prompt.contains("Assistant Tool Call: ping with args: {}"), prompt);
    }

    @Test
    void buildCliPromptListsToolsWithAndWithoutDescriptions() {
        List<ToolSpecification> tools = List.of(
                ToolSpecification.builder()
                        .name("read_file")
                        .description("Reads a file")
                        .build(),
                ToolSpecification.builder().name("no_description").build());

        String prompt = FoundryCliExecutor.buildCliPrompt(oneUserMessage(), tools, new ObjectMapper());

        assertTrue(prompt.contains("--- Available Tools ---"), prompt);
        assertTrue(prompt.contains("- Tool: read_file (Reads a file)"), prompt);
        assertTrue(prompt.contains("- Tool: no_description"), prompt);
        assertFalse(prompt.contains("(null)"), prompt);
    }

    @Test
    void buildCliPromptIgnoresAnEmptyToolList() {
        String prompt = FoundryCliExecutor.buildCliPrompt(oneUserMessage(), List.of(), new ObjectMapper());
        assertFalse(prompt.contains("--- Available Tools ---"), prompt);
        assertEquals("User: hello", prompt);
    }

    // --------------------------------------------------------------------- execute()

    @Test
    void executeReturnsTheResponseText() throws IOException {
        Path exe = cliEmitting("{\"text\":\"hello from foundry\"}");

        ChatResponse response = run(exe, "qwen3-14b", oneUserMessage(), Duration.ofSeconds(30));

        assertEquals("hello from foundry", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
    }

    @Test
    void executeAcceptsABareJsonStringPayload() throws IOException {
        Path exe = cliEmitting("\"plain string response\"");

        ChatResponse response = run(exe, "qwen3-14b", oneUserMessage(), Duration.ofSeconds(30));

        assertEquals("plain string response", response.aiMessage().text());
    }

    @Test
    void executeDelegatesThroughTheFiveArgumentOverload() throws IOException {
        Path exe = cliEmitting("{\"text\":\"via overload\"}");

        ChatResponse response =
                FoundryCliExecutor.execute(exe, "qwen3-14b", oneUserMessage(), Duration.ofSeconds(30), null);

        assertEquals("via overload", response.aiMessage().text());
    }

    @Test
    void executeFallsBackToTheDefaultModelForNullOrBlankNames() throws IOException {
        Path exe = cliEmitting("{\"text\":\"defaulted model\"}");

        assertEquals(
                "defaulted model",
                run(exe, null, oneUserMessage(), Duration.ofSeconds(30))
                        .aiMessage()
                        .text());
        assertEquals(
                "defaulted model",
                run(exe, "   ", oneUserMessage(), Duration.ofSeconds(30))
                        .aiMessage()
                        .text());
        assertEquals(ModelDefaults.DEFAULT_FOUNDRY_MODEL, "qwen3-14b");
    }

    @Test
    void executeReportsANonZeroExitCodeWithStderr() throws IOException {
        Path exe = cliFailing(3, "foundry boom");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> run(exe, "qwen3-14b", oneUserMessage(), Duration.ofSeconds(30)));
        assertTrue(thrown.getMessage().contains("exited with code 3"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("foundry boom"), thrown.getMessage());
    }

    @Test
    void executeReportsANonZeroExitCodeWithoutStderr() throws IOException {
        Path exe = cliFailing(4, "   ");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> run(exe, "qwen3-14b", oneUserMessage(), Duration.ofSeconds(30)));
        assertTrue(thrown.getMessage().contains("exited with code 4"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains(": "), "blank stderr must not add a suffix: " + thrown.getMessage());
    }

    @Test
    void executeKillsTheProcessOnTimeout() throws IOException {
        Path exe = cliScript("sleep 30");

        // Duration.ZERO.toSeconds() == 0, so waitFor returns immediately without blocking the test.
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> run(exe, "qwen3-14b", oneUserMessage(), Duration.ZERO));
        assertTrue(thrown.getMessage().contains("timed out after 0 seconds"), thrown.getMessage());
    }

    @Test
    void executeWrapsAnUnstartableProcess() {
        Path missing = tempDir.resolve("definitely-not-here");

        RuntimeException thrown = assertThrows(
                RuntimeException.class, () -> run(missing, "qwen3-14b", oneUserMessage(), Duration.ofSeconds(30)));
        assertTrue(thrown.getMessage().contains("Failed to execute Foundry CLI request"), thrown.getMessage());
        assertNotNull(thrown.getCause());
    }

    @Test
    void executeWrapsMalformedJson() throws IOException {
        Path exe = cliEmitting("this is not json at all");

        RuntimeException thrown = assertThrows(
                RuntimeException.class, () -> run(exe, "qwen3-14b", oneUserMessage(), Duration.ofSeconds(30)));
        assertTrue(thrown.getMessage().contains("Failed to execute Foundry CLI request"), thrown.getMessage());
    }

    @Test
    void executeReturnsAToolExecutionRequestWhenTheModelCallsATool() throws IOException {
        Path exe = cliEmitting(
                "{\"text\":\"{\\\"tool\\\": \\\"read_file\\\", \\\"parameters\\\": {\\\"path\\\": \\\"a.txt\\\"}}\"}");
        List<ToolSpecification> tools =
                List.of(ToolSpecification.builder().name("read_file").build());

        ChatResponse response = run(exe, oneUserMessage(), tools, Duration.ofSeconds(30));

        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        assertEquals(
                "read_file", response.aiMessage().toolExecutionRequests().get(0).name());
    }

    @Test
    void executeTreatsPlainTextAsAnAnswerEvenWhenToolsAreDeclared() throws IOException {
        Path exe = cliEmitting("{\"text\":\"just prose, no tool call\"}");
        List<ToolSpecification> tools =
                List.of(ToolSpecification.builder().name("read_file").build());

        ChatResponse response = run(exe, oneUserMessage(), tools, Duration.ofSeconds(30));

        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals("just prose, no tool call", response.aiMessage().text());
    }

    @Test
    void executeNeverParsesAToolCallWhenNoToolsAreDeclared() throws IOException {
        Path exe = cliEmitting("{\"text\":\"{\\\"tool\\\": \\\"read_file\\\"}\"}");

        ChatResponse response = run(exe, "qwen3-14b", oneUserMessage(), Duration.ofSeconds(30));

        assertEquals(FinishReason.STOP, response.finishReason(), "no tools declared means no tool call");
    }

    // ---------------------------------------------------------------- parseToolCall()

    @Test
    void parseToolCallRejectsNullOrBlankText() {
        ObjectMapper mapper = new ObjectMapper();
        assertNull(FoundryCliExecutor.parseToolCall(null, mapper));
        assertNull(FoundryCliExecutor.parseToolCall("   ", mapper));
    }

    @Test
    void parseToolCallReadsTheToolKey() {
        ToolExecutionRequest request = FoundryCliExecutor.parseToolCall(
                "{\"tool\": \"read_file\", \"parameters\": {\"path\": \"a.txt\"}}", new ObjectMapper());

        assertNotNull(request);
        assertEquals("read_file", request.name());
        assertTrue(request.arguments().contains("a.txt"));
    }

    @Test
    void parseToolCallFallsBackToTheNameKey() {
        ToolExecutionRequest request = FoundryCliExecutor.parseToolCall(
                "{\"name\": \"write_file\", \"arguments\": {\"path\": \"b.txt\"}}", new ObjectMapper());

        assertNotNull(request);
        assertEquals("write_file", request.name());
        assertTrue(request.arguments().contains("b.txt"));
    }

    @Test
    void parseToolCallAcceptsATextualArgumentsNode() {
        ToolExecutionRequest request = FoundryCliExecutor.parseToolCall(
                "{\"tool\": \"echo\", \"arguments\": \"raw-argument-string\"}", new ObjectMapper());

        assertNotNull(request);
        assertEquals("echo", request.name());
        assertEquals("raw-argument-string", request.arguments());
    }

    @Test
    void parseToolCallUsesTheWholeObjectWhenNoArgumentsKeyIsPresent() {
        ToolExecutionRequest request =
                FoundryCliExecutor.parseToolCall("{\"tool\": \"ping\", \"retries\": 2}", new ObjectMapper());

        assertNotNull(request);
        assertEquals("ping", request.name());
        assertTrue(request.arguments().contains("retries"));
    }

    @Test
    void parseToolCallUnwrapsAFencedCodeBlock() {
        ToolExecutionRequest request = FoundryCliExecutor.parseToolCall(
                "Here you go:\n```json\n{\"tool\": \"read_file\", \"parameters\": {}}\n```", new ObjectMapper());

        assertNotNull(request);
        assertEquals("read_file", request.name());
    }

    @Test
    void parseToolCallReturnsNullWhenNoJsonObjectIsPresent() {
        assertNull(FoundryCliExecutor.parseToolCall("no braces here", new ObjectMapper()));
        assertNull(FoundryCliExecutor.parseToolCall("{\"unterminated\": ", new ObjectMapper()));
    }

    @Test
    void parseToolCallReturnsNullWhenTheObjectCarriesNoToolName() {
        assertNull(FoundryCliExecutor.parseToolCall("{\"parameters\": {\"path\": \"a.txt\"}}", new ObjectMapper()));
        assertNull(FoundryCliExecutor.parseToolCall("{\"tool\": \"   \"}", new ObjectMapper()));
    }

    @Test
    void parseToolCallSwallowsMalformedJson() {
        assertNull(FoundryCliExecutor.parseToolCall("{not: valid: json}", new ObjectMapper()));
    }
}
