package com.amsha.mcp.bridge;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.ToolInvocationGuard;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tool.attempt.ToolOperationIdentity;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.ToolSchemaProvider;
import com.amsha.mcp.config.McpToolAllowlist;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class McpToolBridge {
    private static final String MCP_WORKSPACE_PREFIX = "mcp-client:";

    private static final Logger log = Logger.getLogger(McpToolBridge.class);

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    ToolInvocationGuard toolInvocationGuard;

    @Inject
    McpToolAllowlist allowlist;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ToolSchemaProvider schemaProvider;

    private final Map<String, String> schemaCache = new ConcurrentHashMap<>();

    @PostConstruct
    void warmSchemaCache() {
        log.info("McpToolBridge: Warming schema cache for exposed tools...");
        allowlist.exposedTools().forEach(name -> {
            AgentTool tool = toolRegistry
                    .findTool(name)
                    .orElseThrow(() -> new IllegalStateException(
                            "McpToolBridge: exposed tool '" + name + "' is not registered in ToolRegistry (§4.1)"));
            try {
                schemaCache.put(name, schemaProvider.getJsonSchema(tool));
            } catch (Exception e) {
                log.errorf("Failed to cache MCP schema for tool '%s': %s", name, LogUtil.rootMessage(e));
            }
        });
    }

    public void clearCache() {
        schemaCache.clear();
        log.info("McpToolBridge: Schema cache cleared.");
    }

    public List<McpToolDescriptor> getExposedTools() {
        return allowlist.exposedTools().stream()
                .map(toolNameStr -> Arrays.stream(ToolCapability.values())
                        .filter(cap -> cap.toolName().equals(toolNameStr))
                        .findFirst()
                        .map(cap -> new McpToolDescriptor(cap.toolName(), cap.description(), buildInputSchema(cap)))
                        .orElseGet(() -> {
                            log.warnf(
                                    "McpToolAllowlist entry '%s' has no matching ToolCapability.toolName() - skipped",
                                    toolNameStr);
                            return null;
                        }))
                .filter(Objects::nonNull)
                .toList();
    }

    @Inject
    AuditService auditService;

    public String executeTool(McpToolRequest request) {
        String toolName = request.toolName();
        String paramsJson = request.paramsJson();
        if (!allowlist.exposedTools().contains(toolName)) {
            throw new McpToolNotAllowedException("Tool '" + toolName + "' not in allowlist");
        }

        String mcpClientId = request.getHeader("X-MCP-Client-ID");
        String mcpSessionId = request.getHeader("X-MCP-Session-ID");
        String mcpCapabilities = request.getHeader("X-MCP-Capabilities");
        if (!isValidIdentityPart(mcpClientId) || !isValidIdentityPart(mcpSessionId)) {
            return "Error: MCP client and session identifiers are required.";
        }

        String sessionId = "mcp-" + mcpClientId + "-" + mcpSessionId;
        String userId = "mcp:" + mcpClientId;
        String workspaceId = MCP_WORKSPACE_PREFIX + mcpClientId;
        String role = mapCapabilitiesToRole(mcpCapabilities);

        auditService.record(
                sessionId,
                userId,
                role,
                SecurityEventType.TOOL_INVOKED,
                "Invoked " + toolName + " by " + mcpClientId + " [session=" + mcpSessionId + "]");

        try {
            return WorkspaceContext.callWithActiveWorkspace(
                    workspaceId,
                    () -> WorkspaceContext.run(sessionId, userId, "mcp", AutonomyLevel.SUPERVISED, role, () -> {
                        Map<String, Object> params = parseParameters(paramsJson);
                        if (params == null) {
                            return "Error: Tool parameters are invalid.";
                        }
                        ToolInvocationGuard.GuardDecision decision =
                                evaluateMandatoryGuard(sessionId, role, toolName, params);
                        return executeGuardedTool(decision, toolName, paramsJson);
                    }));
        } catch (Exception e) {
            log.errorf(
                    LogUtil.relevantThrowable(e),
                    "MCP tool invocation failed for '%s': %s",
                    toolName,
                    LogUtil.rootMessage(e));
            return "Error: Tool invocation failed.";
        }
    }

    private Map<String, Object> parseParameters(String paramsJson) {
        try {
            return objectMapper.readValue(paramsJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean isValidIdentityPart(String value) {
        return value != null
                && !value.isBlank()
                && !"unknown".equalsIgnoreCase(value)
                && value.length() <= 128
                && value.chars()
                        .noneMatch(character -> Character.isISOControl(character) || Character.isWhitespace(character));
    }

    private ToolInvocationGuard.GuardDecision evaluateMandatoryGuard(
            String sessionId, String role, String toolName, Map<String, Object> params) {
        try {
            return toolInvocationGuard.evaluate(
                    sessionId, role, AutonomyLevel.SUPERVISED, toolName, params, List.of(), null, List.of());
        } catch (RuntimeException e) {
            log.warnf(
                    LogUtil.relevantThrowable(e),
                    "Mandatory MCP tool guard failed for '%s': %s",
                    toolName,
                    LogUtil.rootMessage(e));
            return new ToolInvocationGuard.GuardDecision.Blocked("Mandatory safety checks are unavailable.");
        }
    }

    private String executeGuardedTool(ToolInvocationGuard.GuardDecision decision, String toolName, String paramsJson) {
        if (decision instanceof ToolInvocationGuard.GuardDecision.Allow) {
            ToolResponse response = toolRegistry.invokeToolWithOperationId(
                    toolName, paramsJson, "mcp-server", ToolOperationIdentity.generateCanonicalUuid());
            return response.success() ? response.output() : "Error: " + response.output();
        }
        if (decision instanceof ToolInvocationGuard.GuardDecision.DurableReplay replay) {
            return replay.succeeded() ? replay.observation() : "Error: " + replay.observation();
        }
        if (decision instanceof ToolInvocationGuard.GuardDecision.Deduplicate duplicate) {
            return duplicate.cachedObservation();
        }
        return "Error: Tool invocation was blocked by mandatory safety checks.";
    }

    private String mapCapabilitiesToRole(String capabilities) {
        if (capabilities != null && capabilities.contains("code_generation")) {
            return AgentRoleEnum.CODER.id();
        }
        if (capabilities != null && capabilities.contains("web_navigation")) {
            return AgentRoleEnum.RESEARCHER.id();
        }
        return AgentRoleEnum.COORDINATOR.id();
    }

    private String buildInputSchema(ToolCapability cap) {
        String toolName = cap.toolName();
        return schemaCache.computeIfAbsent(toolName, name -> {
            AgentTool tool = toolRegistry
                    .findTool(name)
                    .orElseThrow(() -> new IllegalStateException("Tool not found in registry: " + name));
            try {
                return schemaProvider.getJsonSchema(tool);
            } catch (Exception e) {
                log.errorf("Failed to serialize schema for tool '%s': %s", name, LogUtil.rootMessage(e));
                return "{\"type\":\"object\"}";
            }
        });
    }
}
