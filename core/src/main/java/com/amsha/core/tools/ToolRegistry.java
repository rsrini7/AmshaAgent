package com.amsha.core.tools;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.common.util.PathSanitizer;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.ComplianceViolationException;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.config.CoreSpiResolutionValidator;
import com.amsha.core.sandbox.SandboxAttemptLifecycle;
import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.sandbox.SandboxFailureMessages;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.security.AutonomyPolicy;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.tool.DynamicToolInvoker;
import com.amsha.core.tool.attempt.ToolAttemptExecutionService;
import com.amsha.core.tool.attempt.ToolAttemptSandboxLifecycle;
import com.amsha.core.util.ClassUtils;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.security.api.RequiresSecret;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.DynamicToolDescriptor;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Startup;

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
@Startup
@ApplicationScoped
@Unremovable
public class ToolRegistry {

    private static final Logger log = Logger.getLogger(ToolRegistry.class);

    @Inject
    ToolDiscoveryService discoveryService;

    @Inject
    ToolGovernanceGuard governanceGuard;

    @Inject
    ToolSecretExtractor secretExtractor;

    @Inject
    BeanManager beanManager;

    @Inject
    Event<ToolDiscoveryCompleted> toolDiscoveryCompleted;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AuditService auditService;

    @Inject
    ToolAttemptExecutionService toolAttemptExecutionService;

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    Instance<DynamicToolInvoker> dynamicToolInvokers;

    @ConfigProperty(name = "amsha.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @PostConstruct
    void initialize() {
        discoveryService.discover();
        int count = listTools().size();
        log.infof("ToolRegistry: Eager startup discovery complete. Discovered and registered %d tools.", count);
        toolDiscoveryCompleted.fire(new ToolDiscoveryCompleted(count));
    }

    /**
     * Converts a session ID to a safe directory name for all OS platforms.
     * Fix: replace ':' → '_' for Windows path compatibility.
     */
    public static String toSafeDirName(String sessionId) {
        return PathSanitizer.sanitizeForFilesystem(sessionId);
    }

    public List<String> listTools() {
        List<String> names = new ArrayList<>(discoveryService.getToolIndex().keySet());
        if (dynamicToolInvokers != null && !dynamicToolInvokers.isUnsatisfied()) {
            dynamicToolInvokers.forEach(invoker -> invoker.listTools().forEach(tool -> names.add(tool.name())));
        }
        Collections.sort(names);
        return List.copyOf(names);
    }

    public String registeredToolNames() {
        return String.join(", ", listTools());
    }

    public Optional<AgentTool> findTool(String toolName) {
        return Optional.ofNullable(discoveryService.getToolIndex().get(toolName));
    }

    private ToolExecutionContext createExecutionContext(String operationId) {
        return ToolExecutionContextFactory.create(skillRegistry, operationId);
    }

    public ToolResponse invokeTool(String toolName, Map<String, Object> params) {
        return invokeToolWithOperationId(toolName, params, null);
    }

    /** Invokes a tool while propagating the orchestration-owned operation identity. */
    public ToolResponse invokeToolWithOperationId(String toolName, Map<String, Object> params, String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return toolAttemptExecutionService.sanitizeWithoutAttempt(
                    toolName, ToolResponse.failure(409, ToolAttemptExecutionService.MISSING_OPERATION_ID_OUTPUT));
        }
        String stableOperationId = operationId;
        if (toolName != null && (toolName.startsWith("headroom_") || "headroom".equals(toolName))) {
            log.infof(
                    "Intercepted headroom tool call '%s' - returning empty response to prevent model loop.", toolName);
            return ToolResponse.success("No relevant information found or action completed successfully.");
        }

        AgentTool tool = findTool(toolName).orElse(null);
        if (tool == null) {
            if (dynamicToolInvokers != null && !dynamicToolInvokers.isUnsatisfied()) {
                for (DynamicToolInvoker invoker : dynamicToolInvokers) {
                    var descriptor = invoker.findTool(toolName);
                    if (descriptor.isPresent()) {
                        return invokeDynamicTool(invoker, descriptor.get(), toolName, params, stableOperationId);
                    }
                }
            }
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }

        ToolExecutionContext context = createExecutionContext(stableOperationId);
        try {
            governanceGuard.verifyRoleCapability(context, toolName);
            governanceGuard.verifyAutonomy(context, toolName, tool.requiredOperation(), params);
        } catch (SecurityException | IllegalArgumentException e) {
            return toolAttemptExecutionService.sanitizeWithoutAttempt(
                    toolName, ToolResponse.failure(403, e.getMessage()));
        }
        if (params == null) {
            return ToolResponse.failure(400, "Tool parameters are required.");
        }

        log.infof("Invoking tool '%s' (op=%s)", toolName, tool.requiredOperation());
        auditService.record(
                context.sessionId(),
                context.userId(),
                context.agentRole(),
                SecurityEventType.TOOL_INVOKED,
                "tool=" + toolName + " op=" + tool.requiredOperation());

        Map<String, Object> sanitizedParams = sanitizeParameters(tool, params, toolName);
        String executionKind = tool.requiresSandbox()
                ? ToolAttemptExecutionService.EXECUTION_KIND_SANDBOX
                : ToolAttemptExecutionService.EXECUTION_KIND_BUILT_IN;
        if (ToolCapability.HOST_EXEC.toolName().equals(toolName)) {
            executionKind = ToolAttemptExecutionService.EXECUTION_KIND_HOST;
        }
        ToolAttemptExecutionService.Admission admission = toolAttemptExecutionService.admit(
                context.sessionId(),
                stableOperationId,
                toolName,
                sanitizedParams,
                tool.idempotencyMode(),
                executionKind);
        if (!admission.shouldExecute()) {
            return admission.immediateResponse();
        }
        ToolAttemptExecutionService.AttemptHandle handle = admission.handle();
        ToolExecutionContext attemptContext = toolAttemptExecutionService.attach(context, handle);
        return executeAdmitted(
                handle,
                toolName,
                () -> tool.requiresSandbox()
                        ? invokeSandboxed(attemptContext, tool, sanitizedParams, handle)
                        : invokeDirect(attemptContext, tool, sanitizedParams, handle));
    }

    private ToolResponse invokeDynamicTool(
            DynamicToolInvoker invoker,
            DynamicToolDescriptor descriptor,
            String toolName,
            Map<String, Object> params,
            String operationId) {
        ToolExecutionContext context = createExecutionContext(operationId);
        try {
            governanceGuard.verifyAutonomy(context, toolName, descriptor.requiredOperation(), params);
        } catch (SecurityException | IllegalArgumentException e) {
            return toolAttemptExecutionService.sanitizeWithoutAttempt(
                    toolName, ToolResponse.failure(403, e.getMessage()));
        }
        auditService.record(
                context.sessionId(),
                context.userId(),
                context.agentRole(),
                SecurityEventType.TOOL_INVOKED,
                descriptor.requiredOperation().name());
        ToolAttemptExecutionService.Admission admission = toolAttemptExecutionService.admit(
                context.sessionId(),
                operationId,
                toolName,
                params,
                IdempotencyMode.NON_IDEMPOTENT,
                ToolAttemptExecutionService.EXECUTION_KIND_DYNAMIC);
        if (!admission.shouldExecute()) {
            return admission.immediateResponse();
        }
        ToolAttemptExecutionService.AttemptHandle handle = admission.handle();
        return executeAdmitted(handle, toolName, () -> {
            toolAttemptExecutionService.launchAndStart(handle);
            return invoker.invoke(toolName, params, toolAttemptExecutionService.attach(context, handle));
        });
    }

    private Map<String, Object> sanitizeParameters(AgentTool tool, Map<String, Object> params, String toolName) {
        Map<String, Object> sanitized = tool.shouldStripNullParams()
                ? params.entrySet().stream()
                        .filter(entry -> entry.getValue() != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                : new HashMap<>(params);
        if (tool.parametersSchema() != null
                && tool.parametersSchema().properties() != null
                && !tool.parametersSchema().properties().isEmpty()) {
            Set<String> allowedKeys = tool.parametersSchema().properties().keySet();
            sanitized.keySet().removeIf(key -> {
                boolean remove = !allowedKeys.contains(key);
                if (remove) {
                    log.warnf("[ToolRegistry] Stripping unknown param '%s' from tool '%s'", key, toolName);
                }
                return remove;
            });
        }
        return sanitized;
    }

    private ToolResponse executeAdmitted(
            ToolAttemptExecutionService.AttemptHandle handle, String toolName, Supplier<ToolResponse> executor) {
        try {
            return toolAttemptExecutionService.settle(handle, toolName, executor.get());
        } catch (RuntimeException e) {
            if (e instanceof ComplianceViolationException complianceViolation) {
                throw complianceViolation;
            }
            log.errorf(
                    LogUtil.relevantThrowable(e),
                    "Tool attempt execution failed for '%s': %s",
                    toolName,
                    LogUtil.rootMessage(e));
            try {
                return toolAttemptExecutionService.settle(
                        handle, toolName, ToolResponse.infraFailure("Tool execution outcome could not be confirmed."));
            } catch (RuntimeException settlementFailure) {
                log.errorf(
                        LogUtil.relevantThrowable(settlementFailure),
                        "Tool attempt settlement failed for '%s': %s",
                        toolName,
                        LogUtil.rootMessage(settlementFailure));
                return ToolResponse.infraFailure("Tool result could not be durably settled.");
            }
        }
    }

    private ToolResponse invokeSandboxed(
            ToolExecutionContext ctx,
            AgentTool tool,
            Map<String, Object> params,
            ToolAttemptExecutionService.AttemptHandle handle) {
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
            Optional<SandboxExecutionPort> sandboxPort =
                    CoreSpiResolutionValidator.resolveOptional(beanManager, SandboxExecutionPort.class);
            if (sandboxPort.isEmpty()) {
                log.warnf("Tool '%s' requires sandbox isolation, but SandboxExecutionPort is unavailable.", toolName);
                return ToolResponse.infraFailure(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE);
            }

            var command = tool.sandboxCommand(ctx, params);
            ToolExecutionContext attemptContext = toolAttemptExecutionService.attach(ctx, handle);
            SandboxExecutionPort executor = sandboxPort.get();
            handle.afterSettlement(() -> releaseSettledSandboxAttempt(handle, executor, attemptContext));
            SandboxAttemptLifecycle lifecycle = new ToolAttemptSandboxLifecycle(toolAttemptExecutionService, handle);
            SandboxResult result =
                    executor.executeSandboxed(attemptContext, workspaceRoot, command, requiredSecrets, lifecycle);

            if (result == null || result.isInfraFailure()) {
                log.warnf("Tool '%s' sandbox execution encountered an infrastructure failure.", toolName);
                return ToolResponse.infraFailure(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE);
            }

            if (result.timedOut()) {
                return ToolResponse.failure(
                        124,
                        result.output(),
                        ToolAttemptExecutionService.TIMEOUT_ERROR_CODE,
                        Span.current().getSpanContext().getTraceId(),
                        "TimeoutException");
            }

            if (!result.success()) {
                return ToolResponse.failure(1, SandboxFailureMessages.SANDBOX_EXECUTION_FAILED_MESSAGE);
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
            log.errorf(
                    "Tool '%s' sandboxed execution infra failure (type=%s)",
                    toolName, e.getClass().getSimpleName());
            return ToolResponse.infraFailure(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE);
        }
    }

    private void releaseSettledSandboxAttempt(
            ToolAttemptExecutionService.AttemptHandle handle,
            SandboxExecutionPort executor,
            ToolExecutionContext context) {
        if (!canReleaseSandboxAttempt(handle.status())) {
            return;
        }
        try {
            executor.releaseSettledAttempt(context);
        } catch (RuntimeException e) {
            log.warnf(
                    LogUtil.relevantThrowable(e),
                    "Failed to release settled sandbox attempt: %s",
                    LogUtil.rootMessage(e));
        }
    }

    static boolean canReleaseSandboxAttempt(ToolAttemptStatus status) {
        return switch (status) {
            case SETTLED_SUCCESS, SETTLED_ERROR, TIMED_OUT, CANCELLED_BEFORE_LAUNCH -> true;
            case ADMITTED, LAUNCH_REQUESTED, RUNNING, INDETERMINATE -> false;
        };
    }

    private ToolResponse invokeDirect(
            ToolExecutionContext ctx,
            AgentTool tool,
            Map<String, Object> params,
            ToolAttemptExecutionService.AttemptHandle handle) {
        String toolName = tool.name();
        log.infof("Invoking tool '%s' directly (sandbox opted out)", toolName);
        governanceGuard.recordToolAudit(ctx, toolName);

        try {
            toolAttemptExecutionService.launchAndStart(handle);
            ctx = toolAttemptExecutionService.attach(ctx, handle);
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
        // Unwrap Quarkus ArC InvocationException to get the real root cause.
        // ArC wraps all exceptions from CDI proxy method dispatch in InvocationException.
        Exception unwrapped = e;
        while (unwrapped.getClass().getSimpleName().equals("InvocationException")
                && unwrapped.getCause() instanceof Exception cause) {
            unwrapped = cause;
        }
        String raw = unwrapped.getMessage() != null ? unwrapped.getMessage() : ClassUtils.getCleanName(unwrapped);
        String traceId = Span.current().getSpanContext().getTraceId();

        String friendly;
        if (toolName.equalsIgnoreCase(ToolCapability.GITHUB.toolName()) && raw.contains("Cannot run program \"gh\"")) {
            friendly = "GitHub integration is not available in this environment. The `gh` CLI is not installed.";
        } else {
            friendly = "EXECUTION_FAILED: " + raw;
        }

        log.errorf("Tool '%s' execution failed: %s", toolName, raw);
        ToolResponse response =
                ToolResponse.failure(1, friendly, "TOOL_EXECUTION_FAILED", traceId, ClassUtils.getCleanName(unwrapped));
        governanceGuard.recordToolFailureAudit(ctx, toolName, response);
        return response;
    }

    public ToolResponse invokeTool(String toolName, String paramsJson, String sessionId) {
        return invokeToolWithOperationId(toolName, paramsJson, sessionId, null);
    }

    /** Invokes a serialized tool request while propagating its operation identity. */
    public ToolResponse invokeToolWithOperationId(
            String toolName, String paramsJson, String sessionId, String operationId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = objectMapper.readValue(paramsJson, Map.class);
            return invokeToolWithOperationId(toolName, params, operationId);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize tool parameters for " + toolName, e);
        }
    }

    public record ToolDescriptor(String name, String description, String inputSchema) {}

    public List<ToolDescriptor> listToolDescriptors() {
        List<ToolDescriptor> descriptors =
                new ArrayList<>(discoveryService.getDescriptorCache().values());
        if (dynamicToolInvokers != null && !dynamicToolInvokers.isUnsatisfied()) {
            dynamicToolInvokers.forEach(invoker -> invoker.listTools()
                    .forEach(tool ->
                            descriptors.add(new ToolDescriptor(tool.name(), tool.description(), tool.inputSchema()))));
        }
        return List.copyOf(descriptors);
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
