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

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.MultimodalRequests;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Executes chat and completion requests directly via local Microsoft Foundry CLI ('foundry') binary.
 */
public final class FoundryCliExecutor {

    private FoundryCliExecutor() {}

    public static boolean isAvailable() {
        return resolveCliPath() != null;
    }

    public static Path resolveCliPath() {
        String customExe = System.getenv("AMSHA_FOUNDRY_EXE");
        if (customExe != null && !customExe.isBlank()) {
            Path p = Path.of(customExe);
            if (Files.isExecutable(p)) {
                return p;
            }
        }

        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank()) {
            Path homebrewBin = Path.of("/opt/homebrew/bin/foundry");
            if (Files.isExecutable(homebrewBin)) {
                return homebrewBin;
            }
            Path localBin = Path.of(userHome, ".local", "bin", "foundry");
            if (Files.isExecutable(localBin)) {
                return localBin;
            }
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path candidate = Path.of(dir, "foundry");
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
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
                sb.append("User: ")
                        .append(MultimodalRequests.textOf(usr, "foundry"))
                        .append("\n\n");
            } else if (msg instanceof AiMessage ai) {
                if (ai.text() != null && !ai.text().isBlank()) {
                    sb.append("Assistant: ").append(ai.text()).append("\n\n");
                }
                if (ai.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        sb.append("Assistant Tool Call: ")
                                .append(req.name())
                                .append(" with args: ")
                                .append(req.arguments() != null ? req.arguments() : "{}")
                                .append("\n\n");
                    }
                }
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
            Path foundryExe, String model, List<ChatMessage> messages, Duration timeout, ObjectMapper objectMapper) {
        return execute(foundryExe, model, messages, null, timeout, objectMapper);
    }

    public static ChatResponse execute(
            Path foundryExe,
            String model,
            List<ChatMessage> messages,
            List<ToolSpecification> tools,
            Duration timeout,
            ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
        String prompt = buildCliPrompt(messages, tools, mapper);
        String resolvedModel = (model != null && !model.isBlank()) ? model : ModelDefaults.DEFAULT_FOUNDRY_MODEL;

        try {
            Process process =
                    new ProcessBuilder(foundryExe.toString(), "complete", resolvedModel, prompt, "-o", "json").start();

            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Foundry CLI timed out after " + timeout.toSeconds() + " seconds.");
            }

            if (process.exitValue() != 0) {
                String err;
                try (InputStream es = process.getErrorStream()) {
                    err = new String(es.readAllBytes(), StandardCharsets.UTF_8).trim();
                }
                throw new IllegalStateException(
                        "Foundry CLI exited with code " + process.exitValue() + (err.isBlank() ? "" : ": " + err));
            }

            try (InputStream is = process.getInputStream()) {
                JsonNode root = mapper.readTree(is);
                String text = root.path("text").asText("");
                if (text.isBlank() && root.isTextual()) {
                    text = root.asText();
                }

                ToolExecutionRequest toolCall =
                        (tools != null && !tools.isEmpty()) ? parseToolCall(text, mapper) : null;

                if (toolCall != null) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(List.of(toolCall)))
                            .finishReason(FinishReason.TOOL_EXECUTION)
                            .build();
                }

                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(text))
                        .finishReason(FinishReason.STOP)
                        .build();
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute Foundry CLI request: " + LogUtil.rootMessage(e), e);
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
