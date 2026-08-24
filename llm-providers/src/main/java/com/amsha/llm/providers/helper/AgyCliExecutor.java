package com.amsha.llm.providers.helper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.amsha.common.util.LogUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Executes chat requests directly via local Antigravity CLI ('agy') binary without API keys.
 */
public final class AgyCliExecutor {

    private AgyCliExecutor() {}

    public static boolean isAvailable() {
        return resolveCliPath() != null;
    }

    public static Path resolveCliPath() {
        String customExe = System.getenv("ANTIGRAVITY_AGENTAPI_EXE");
        if (customExe != null && !customExe.isBlank()) {
            Path p = Path.of(customExe);
            if (Files.isExecutable(p)) {
                return p;
            }
        }

        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank()) {
            Path localBin = Path.of(userHome, ".local", "bin", "agy");
            if (Files.isExecutable(localBin)) {
                return localBin;
            }
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path candidate = Path.of(dir, "agy");
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public static String resolveCliModel(String model) {
        if (model == null || model.isBlank()) {
            return "Gemini 3.7 Flash (Low)";
        }
        String lower = model.toLowerCase(Locale.ROOT);
        if (lower.contains("3.7") || lower.contains("flash-latest") || lower.contains("flash-lite")) {
            return "Gemini 3.7 Flash (Low)";
        }
        if (lower.contains("3.6")) {
            return "Gemini 3.6 Flash (Low)";
        }
        if (lower.contains("3.5")) {
            return "Gemini 3.5 Flash (Low)";
        }
        if (lower.contains("3.1") || lower.contains("pro")) {
            return "Gemini 3.1 Pro (Low)";
        }
        if (lower.contains("sonnet") || lower.contains("claude")) {
            return "Claude Sonnet 4.6 (Thinking)";
        }
        if (lower.contains("gpt") || lower.contains("oss")) {
            return "GPT-OSS 120B (Medium)";
        }
        return model;
    }

    public static String buildCliPrompt(List<ChatMessage> messages) {
        return buildCliPrompt(messages, null, null);
    }

    public static String buildCliPrompt(
            List<ChatMessage> messages, List<ToolSpecification> tools, ObjectMapper mapper) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sys) {
                sb.append("System Instructions:\n").append(sys.text()).append("\n\n");
            } else if (msg instanceof UserMessage usr) {
                sb.append("User: ").append(usr.singleText()).append("\n\n");
            } else if (msg instanceof AiMessage ai
                    && ai.text() != null
                    && !ai.text().isBlank()) {
                sb.append("Assistant: ").append(ai.text()).append("\n\n");
            } else if (msg instanceof ToolExecutionResultMessage toolRes) {
                sb.append("Tool [")
                        .append(toolRes.toolName())
                        .append("] Output: ")
                        .append(toolRes.text())
                        .append("\n\n");
            }
        }

        if (tools != null && !tools.isEmpty()) {
            sb.append("--- Available Tools ---\n");
            for (ToolSpecification tool : tools) {
                sb.append("- Tool: ").append(tool.name());
                if (tool.description() != null && !tool.description().isBlank()) {
                    sb.append(" (").append(tool.description()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\nTo call a tool, you MUST respond ONLY with a JSON object:\n")
                    .append("{\"tool\": \"<tool_name>\", \"parameters\": { ... }}\n\n");
        }

        return sb.toString().trim();
    }

    public static ChatResponse execute(
            Path agyExe, String model, List<ChatMessage> messages, Duration timeout, ObjectMapper objectMapper) {
        return execute(agyExe, model, messages, null, timeout, objectMapper);
    }

    public static ChatResponse execute(
            Path agyExe,
            String model,
            List<ChatMessage> messages,
            List<ToolSpecification> tools,
            Duration timeout,
            ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
        String prompt = buildCliPrompt(messages, tools, mapper);
        String cliModel = resolveCliModel(model);

        try {
            Process process = new ProcessBuilder(
                            agyExe.toString(),
                            "-p",
                            prompt,
                            "--model",
                            cliModel,
                            "--disable-slash-commands",
                            "--output-format",
                            "json")
                    .start();

            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Antigravity CLI timed out after " + timeout.toSeconds() + " seconds.");
            }

            if (process.exitValue() != 0) {
                String err;
                try (InputStream es = process.getErrorStream()) {
                    err = new String(es.readAllBytes(), StandardCharsets.UTF_8).trim();
                }
                throw new IllegalStateException(
                        "Antigravity CLI exited with code " + process.exitValue() + (err.isBlank() ? "" : ": " + err));
            }

            try (InputStream is = process.getInputStream()) {
                JsonNode root = mapper.readTree(is);
                if ("ERROR".equalsIgnoreCase(root.path("status").asText())) {
                    throw new IllegalStateException(
                            "Antigravity CLI error: " + root.path("error").asText());
                }

                String text = root.path("response").asText("");
                JsonNode usageNode = root.path("usage");
                int inTok = usageNode.path("input_tokens").asInt(0);
                int outTok = usageNode.path("output_tokens").asInt(0);
                TokenUsage usage = (inTok > 0 || outTok > 0) ? new TokenUsage(inTok, outTok) : null;

                ToolExecutionRequest toolCall =
                        (tools != null && !tools.isEmpty()) ? parseToolCall(text, mapper) : null;

                if (toolCall != null) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(List.of(toolCall)))
                            .tokenUsage(usage)
                            .finishReason(FinishReason.TOOL_EXECUTION)
                            .build();
                }

                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(text))
                        .tokenUsage(usage)
                        .finishReason(FinishReason.STOP)
                        .build();
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute Antigravity CLI request: " + LogUtil.rootMessage(e), e);
        }
    }

    public static ToolExecutionRequest parseToolCall(String text, ObjectMapper mapper) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBackticks = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBackticks > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastBackticks).trim();
            }
        }
        int openBrace = trimmed.indexOf('{');
        int closeBrace = trimmed.lastIndexOf('}');
        if (openBrace >= 0 && closeBrace > openBrace) {
            try {
                String candidate = trimmed.substring(openBrace, closeBrace + 1);
                JsonNode json = mapper.readTree(candidate);
                String toolName = null;
                if (json.hasNonNull("tool")) {
                    toolName = json.get("tool").asText();
                } else if (json.hasNonNull("name")) {
                    toolName = json.get("name").asText();
                }
                if (toolName != null && !toolName.isBlank()) {
                    JsonNode paramsNode = json.has("parameters")
                            ? json.get("parameters")
                            : (json.has("arguments") ? json.get("arguments") : json);
                    String args = paramsNode.isTextual() ? paramsNode.asText() : mapper.writeValueAsString(paramsNode);
                    return ToolExecutionRequest.builder()
                            .name(toolName.trim())
                            .arguments(args)
                            .build();
                }
            } catch (Exception _) {
                // Not valid tool JSON
            }
        }
        return null;
    }
}
