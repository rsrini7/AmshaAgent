package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import com.amsha.core.security.InputSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TestDataCollectionService {

    private static final Logger log = Logger.getLogger(TestDataCollectionService.class);

    @ConfigProperty(name = "amsha.data-dir", defaultValue = "data")
    String dataDir;

    @Inject
    ObjectMapper mapper;

    @Inject
    @InputSanitizer.PII
    InputSanitizer piiSanitizer;

    private final ReentrantLock toolRiskLock = new ReentrantLock();
    private final ReentrantLock answerQualityLock = new ReentrantLock();
    private final ReentrantLock intentLock = new ReentrantLock();

    public void recordIntentCandidate(String expectedLabel, String userMessage) {
        ObjectNode input = mapper.createObjectNode();
        input.put("message", piiSanitizer.sanitize(userMessage));
        writeCandidate("intent", expectedLabel, input, intentLock);
    }

    public void recordToolRiskCandidate(String expectedLabel, ToolRiskShadowContext context) {
        ObjectNode input = mapper.createObjectNode();
        input.put("agent_role", piiSanitizer.sanitize(context.agentRole()));
        input.put("autonomy", piiSanitizer.sanitize(context.autonomyLevel().name()));
        input.put("tool_name", piiSanitizer.sanitize(context.toolName()));
        input.put("goal", piiSanitizer.sanitize(context.goal()));

        input.set("parameters", sanitizeNode(mapper.valueToTree(new TreeMap<>(context.parameters()))));

        writeCandidate("tool_risk", expectedLabel, input, toolRiskLock);
    }

    public void recordAnswerQualityCandidate(String expectedLabel, AnswerQualityShadowContext context) {
        ObjectNode input = mapper.createObjectNode();
        input.put("agent_role", piiSanitizer.sanitize(context.agentRole()));
        input.put("goal", piiSanitizer.sanitize(context.goal()));
        input.put("answer", piiSanitizer.sanitize(context.answer()));
        input.put("tool_invoked_in_turn", context.toolInvokedInTurn());
        writeCandidate("answer_quality", expectedLabel, input, answerQualityLock);
    }

    private void writeCandidate(String taskStr, String expectedLabel, ObjectNode inputNode, ReentrantLock lock) {
        String contentHash = computeSHA256(inputNode.toString());

        ObjectNode record = mapper.createObjectNode();
        record.put("id", taskStr.replace('_', '-') + "-" + contentHash);
        record.put("task", taskStr);
        record.put("label", expectedLabel);
        record.put("split", "train");
        record.set("input", inputNode);

        ObjectNode source = record.putObject("source");
        source.put("kind", "rest_http_test");
        source.put("ref", "automated_suite");
        source.put("license", "Apache-2.0");

        ObjectNode review = record.putObject("review");
        review.put("status", "pending");
        review.put("reviewer", "");
        review.put("reviewed_at", "");
        review.put("notes", "");

        appendSafely(taskStr.replace('_', '-'), record, lock);
    }

    private void appendSafely(String taskStr, ObjectNode record, ReentrantLock lock) {
        lock.lock();
        try {
            Path file = Paths.get(dataDir, "classifier-training", "review-working", taskStr + ".autotest.jsonl");
            Files.createDirectories(file.getParent());
            byte[] payload = (mapper.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                            file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                    FileLock _ = channel.lock()) {
                channel.write(ByteBuffer.wrap(payload));
            }
        } catch (Exception e) {
            log.error("Failed to append test data collection record", e);
        } finally {
            lock.unlock();
        }
    }

    private JsonNode sanitizeNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(piiSanitizer.sanitize(node.textValue()));
        }
        if (node.isObject()) {
            ObjectNode sanitized = mapper.createObjectNode();
            node.fields().forEachRemaining(entry -> sanitized.set(entry.getKey(), sanitizeNode(entry.getValue())));
            return sanitized;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, sanitizeNode(array.get(i)));
            }
        }
        return node;
    }

    private String computeSHA256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
