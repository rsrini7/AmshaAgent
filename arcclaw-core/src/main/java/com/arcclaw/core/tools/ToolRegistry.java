package com.arcclaw.core.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import com.arcclaw.core.sandbox.SandboxResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.AutonomyPolicy;
import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.util.ClassUtils;
import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.util.PathSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * CDI-based tool registry with governance enforcement.
 * <p>
 * Discovers all {@link AgentTool} implementations via {@code Instance<AgentTool>}
 * at runtime. Before invoking any tool, the registry:
 * <ol>
 * <li>Enforces role-capability hard block (schema filter is soft - LLM can hallucinate past it)</li>
 * <li>Verifies autonomy governance via {@link AutonomyPolicy}</li>
 * <li>Reflectively reads {@link RequiresSecret} annotations from the tool's
 *     {@code execute()} method to build the secrets map</li>
 * <li>Delegates execution to the tool implementation</li>
 * </ol>
 * <p>
 * Audit userId/agentRole are sourced from {@link WorkspaceContext}/{@link WorkspaceScopeHolder}
 * ScopedValues - NOT MDC. MDC is ThreadLocal and dies at virtual thread boundaries;
 * ScopedValues are propagated correctly across Quarkus virtual threads.
 */
@ApplicationScoped
@Unremovable
public class ToolRegistry {

    private static final Logger log = Logger.getLogger(ToolRegistry.class);

    @Inject ToolDiscoveryService discoveryService;
    @Inject ToolGovernanceGuard governanceGuard;
    @Inject ToolSecretExtractor secretExtractor;
    @Inject ProcessIsolationExecutor isolationExecutor;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @PostConstruct
    void initialize() {
        discoveryService.discover();
    }

    void onStart(@Observes StartupEvent ev) {
        log.info("ToolRegistry: Eager startup verification complete.");
    }

    /**
     * Converts a session ID to a safe directory name for all OS platforms.
     * Fix: replace ':' → '_' for Windows path compatibility.
     */
    public static String toSafeDirName(String sessionId) {
        return PathSanitizer.sanitizeForFilesystem(sessionId);
    }

    public List<String> listTools() {
        return List.copyOf(discoveryService.getToolIndex().keySet());
    }

    public String registeredToolNames() {
        return String.join(", ", listTools());
    }

    public Optional<AgentTool> findTool(String toolName) {
        return Optional.ofNullable(discoveryService.getToolIndex().get(toolName));
    }

    /**
     * Invokes a tool by name after verifying governance constraints.
     */
    public ToolResponse invokeTool(String toolName, Map<String, Object> params) {
        var tool = findTool(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolName));

        ToolExecutionContext ctx = new ToolExecutionContext(
                WorkspaceContext.SESSION_ID.isBound() ? WorkspaceContext.SESSION_ID.get() : "system",
                WorkspaceContext.getUserIdOrUnknown(),
                WorkspaceContext.CHAT_ID.isBound() ? WorkspaceContext.CHAT_ID.get() : "system",
                WorkspaceContext.AGENT_ROLE.isBound() ? WorkspaceContext.AGENT_ROLE.get() : "coordinator",
                WorkspaceContext.AUTONOMY.isBound() ? WorkspaceContext.AUTONOMY.get() : AutonomyLevel.SUPERVISED,
                Span.current().getSpanContext().getTraceId(),
                WorkspaceContext.CHANNEL_TYPE.isBound() ? WorkspaceContext.CHANNEL_TYPE.get() : null,
                WorkspaceContext.CHANNEL_PLATFORM_ID.isBound() ? WorkspaceContext.CHANNEL_PLATFORM_ID.get() : null,
                WorkspaceContext.RAW_USER_GOAL.isBound() ? WorkspaceContext.RAW_USER_GOAL.get() : null,
                WorkspaceContext.SUGGESTED_CRON.isBound() ? WorkspaceContext.SUGGESTED_CRON.get() : null,
                WorkspaceContext.SUGGESTED_NEXT_FIRE_TIME.isBound() ? WorkspaceContext.SUGGESTED_NEXT_FIRE_TIME.get() : null,
                WorkspaceContext.SUGGESTED_CRON_SOURCE.isBound() ? WorkspaceContext.SUGGESTED_CRON_SOURCE.get() : null
        );

        // 1. Governance checks
        try {
            governanceGuard.verifyRoleCapability(ctx, toolName);
            governanceGuard.verifyAutonomy(ctx, toolName, tool.requiredOperation(), params);
        } catch (SecurityException | IllegalArgumentException e) {
            return ToolResponse.failure(403, e.getMessage());
        }

        log.infof("Invoking tool '%s' (operation=%s)", toolName, tool.requiredOperation());

        // 2. Param stripping
        Map<String, Object> sanitizedParams;
        if (tool.shouldStripNullParams()) {
            sanitizedParams = params.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        } else {
            sanitizedParams = new HashMap<>(params);
        }

        // §Fix: Strictly strip parameters NOT defined in the tool's schema.
        // LLMs frequently hallucinate extra parameters. While ScheduleTaskTool now supports 
        // 'executionRole', other tools might crash or return a 400 if they receive unknown keys.
        if (tool.parametersSchema() != null 
                && tool.parametersSchema().properties() != null
                && !tool.parametersSchema().properties().isEmpty()) {
            Set<String> allowedKeys = tool.parametersSchema().properties().keySet();
            sanitizedParams.keySet().removeIf(key -> {
                boolean remove = !allowedKeys.contains(key);
                if (remove) {
                    log.warnf("[ToolRegistry] Stripping unknown param '%s' from tool '%s'", key, toolName);
                }
                return remove;
            });
        }

        // 3. Execution Path
        if (tool.requiresSandbox()) {
            return invokeSandboxed(ctx, tool, sanitizedParams);
        } else {
            return invokeDirect(ctx, tool, sanitizedParams);
        }
    }

    private ToolResponse invokeSandboxed(ToolExecutionContext ctx, AgentTool tool, Map<String, Object> params) {
        String toolName = tool.name();
        governanceGuard.recordToolAudit(ctx, toolName);
        var requiredSecrets = secretExtractor.extractRequiredSecrets(tool.getClass(), "sandboxCommand");

        String safeDir = toSafeDirName(ctx.sessionId());
        var workspaceRoot = Path.of(workspaceRootDir, safeDir);

        try {
            if (!Files.exists(workspaceRoot)) {
                Files.createDirectories(workspaceRoot);
            }

            tool.prepareWorkspace(workspaceRoot, params);
            var command = tool.sandboxCommand(ctx, params);

            SandboxResult result = isolationExecutor.executeSandboxed(
                    ctx.sessionId(), workspaceRoot,
                    command, requiredSecrets);

            if (result.isInfraFailure()) {
                return ToolResponse.infraFailure(result.reason());
            }

            if (!result.success()) {
                return ToolResponse.failure(1, result.reason());
            }

            String postProcessedOutput = tool.postProcessOutput(result.output(), params);
            return ToolResponse.success(postProcessedOutput);

        } catch (IllegalArgumentException e) {
            log.warnf("Tool '%s' parameter validation failed: %s", toolName, LogUtil.rootMessage(e));
            return ToolResponse.failure(1, e.getMessage());
        } catch (SecurityException e) {
            return ToolResponse.failure(1, "Security violation: " + e.getMessage());
        } catch (Exception e) {
            if (e instanceof ComplianceViolationException cve) throw cve;
            return handleExecutionException(ctx, toolName, e);
        }
    }
    private ToolResponse invokeDirect(ToolExecutionContext ctx, AgentTool tool, Map<String, Object> params) {
        String toolName = tool.name();
        log.infof("Invoking tool '%s' directly (sandbox opted out)", toolName);
        governanceGuard.recordToolAudit(ctx, toolName);

        try {
            ToolResponse response = tool.execute(ctx, params);
            if (response instanceof ToolResponse.ToolFailure failure) {
                governanceGuard.recordToolFailureAudit(ctx, toolName, failure);
            }
            return response;
        } catch (Exception e) {
            if (e instanceof ComplianceViolationException cve) throw cve;
            return handleExecutionException(ctx, toolName, e);
        }
    }

    private ToolResponse handleExecutionException(ToolExecutionContext ctx, String toolName, Exception e) {
        String raw = e.getMessage() != null ? e.getMessage() : ClassUtils.getCleanName(e);
        String traceId = Span.current().getSpanContext().getTraceId();
        
        String friendly;
        if (toolName.equalsIgnoreCase(ToolCapability.GITHUB.toolName()) && raw.contains("Cannot run program \"gh\"")) {
            friendly = "GitHub integration is not available in this environment. The `gh` CLI is not installed.";
        } else {
            friendly = "EXECUTION_FAILED: " + raw;
        }

        log.errorf("Tool '%s' execution failed: %s", toolName, raw);
        ToolResponse response = ToolResponse.failure(1, friendly, "TOOL_EXECUTION_FAILED", traceId, ClassUtils.getCleanName(e));
        governanceGuard.recordToolFailureAudit(ctx, toolName, response);
        return response;
    }

    public ToolResponse invokeTool(String toolName, String paramsJson, String sessionId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = objectMapper.readValue(paramsJson, Map.class);
            return invokeTool(toolName, params);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize tool parameters for " + toolName, e);
        }
    }

    public record ToolDescriptor(String name, String description, String inputSchema) {}

    public List<ToolDescriptor> listToolDescriptors() {
        return List.copyOf(discoveryService.getDescriptorCache().values());
    }
    public Map<String, String> extractRequiredSecrets(AgentTool tool) {
        return discoveryService.getSecretsCache().getOrDefault(tool.name(), Collections.emptyMap());
    }

    /**
     * Static bridge for legacy callers.
     * <p>
     * NOTE: plain instantiation - ToolSecretExtractor has no CDI dependencies.
     * If dependencies are added in the future, this bridge must be refactored
     * to use Arc.container().instance(ToolSecretExtractor.class).get().
     */
    public static Map<String, String> extractRequiredSecrets(Class<? extends AgentTool> toolClass, String methodName) {
        return new ToolSecretExtractor().extractRequiredSecrets(toolClass, methodName);
    }
}
