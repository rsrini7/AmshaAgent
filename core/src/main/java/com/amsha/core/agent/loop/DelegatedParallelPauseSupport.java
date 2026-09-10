package com.amsha.core.agent.loop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.message.UserMessages;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/** Detects delegated pauses in a parallel batch and preserves other results. */
final class DelegatedParallelPauseSupport {

    private static final Logger log = Logger.getLogger(DelegatedParallelPauseSupport.class);

    private DelegatedParallelPauseSupport() {}

    static Optional<Pause> find(List<ParallelToolCall> completed, ObjectMapper objectMapper) {
        for (ParallelToolCall call : completed) {
            String observation = call.observation();
            if (observation == null || !observation.contains(UserMessages.DELEGATION_PAUSED_MARKER)) {
                continue;
            }
            String toolName = UserMessages.extractPausedTool(observation);
            String paramsJson = UserMessages.extractPausedParamsJson(observation);
            if (toolName == null || toolName.isBlank() || paramsJson == null) {
                continue;
            }
            try {
                Map<String, Object> params = objectMapper.readValue(paramsJson, new TypeReference<>() {});
                return Optional.of(new Pause(call, toolName, params));
            } catch (Exception e) {
                log.warnf("[PARALLEL] Invalid delegated pause payload: %s", LogUtil.rootMessage(e));
            }
        }
        return Optional.empty();
    }

    static Map<String, ToolResponse> completedResults(
            List<ParallelToolCall> dedupCalls, List<ParallelToolCall> allowedCompleted, Pause pause) {
        Map<String, ToolResponse> results = new LinkedHashMap<>();
        add(results, dedupCalls, pause);
        add(results, allowedCompleted, pause);
        return results;
    }

    private static void add(Map<String, ToolResponse> results, List<ParallelToolCall> calls, Pause pause) {
        for (ParallelToolCall call : calls) {
            if (Objects.equals(call.callId(), pause.call().callId()) || isDelegatedPause(call)) {
                continue;
            }
            String key = call.callId() == null || call.callId().isBlank() ? call.toolName() : call.callId();
            ToolResponse response = call.success()
                    ? ToolResponse.success(call.observation())
                    : ToolResponse.failure(1, call.observation());
            results.put(key, response);
        }
    }

    private static boolean isDelegatedPause(ParallelToolCall call) {
        return call.observation() != null && call.observation().contains(UserMessages.DELEGATION_PAUSED_MARKER);
    }

    record Pause(ParallelToolCall call, String toolName, Map<String, Object> params) {}
}
