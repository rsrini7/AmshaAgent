package com.amsha.core.agent.loop;

import java.util.LinkedHashMap;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Formats tool execution summary for display in resume headers.
 */
public final class ResumeToolInfoFormatter {

    private static final Logger log = Logger.getLogger(ResumeToolInfoFormatter.class);

    private ResumeToolInfoFormatter() {}

    public static String format(AgentCheckpoint checkpoint, ObjectMapper objectMapper) {
        if (checkpoint == null) return null;
        String toolName = checkpoint.getPendingToolName();
        if (toolName == null || toolName.isBlank()) return null;

        StringBuilder sb = new StringBuilder();

        if (checkpoint.isParallelBatch()) {
            String[] tools = toolName.substring(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX.length())
                    .split(",", -1);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String t : tools) {
                counts.put(t, counts.getOrDefault(t, 0) + 1);
            }
            boolean first = true;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey());
                if (e.getValue() > 1) sb.append(" (").append(e.getValue()).append("x)");
            }
        } else {
            sb.append(toolName);
            String paramsJson = checkpoint.getPendingToolParametersJson();
            if (paramsJson != null && !paramsJson.isBlank() && objectMapper != null) {
                try {
                    Map<String, Object> params =
                            objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
                    if (!params.isEmpty()) {
                        sb.append("\n");
                        for (Map.Entry<String, Object> e : params.entrySet()) {
                            String val = e.getValue() instanceof String s
                                    ? s
                                    : objectMapper.writeValueAsString(e.getValue());
                            if (val.length() > 200) val = val.substring(0, 200) + "…";
                            sb.append("  ")
                                    .append(e.getKey())
                                    .append(": ")
                                    .append(val)
                                    .append("\n");
                        }
                    }
                } catch (Exception e) {
                    log.debugf("Failed to format params JSON, using raw: %s", LogUtil.rootMessage(e));
                    String display = paramsJson.length() > 500 ? paramsJson.substring(0, 500) + "…" : paramsJson;
                    sb.append("\n  ").append(display);
                }
            }
        }

        return sb.toString().stripTrailing();
    }
}
