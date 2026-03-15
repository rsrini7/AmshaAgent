package com.arcclaw.core.tools;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.AutonomyPolicy;
import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.RequiresSecrets;
import com.arcclaw.core.security.ToolOperation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.arcclaw.core.util.MdcKeys;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * CDI-based tool registry with governance enforcement.
 * <p>
 * Discovers all {@link AgentTool} implementations via
 * {@code Instance<AgentTool>}
 * at runtime. Before invoking any tool, the registry:
 * <ol>
 * <li>Verifies autonomy governance via {@link AutonomyPolicy}</li>
 * <li>Reflectively reads {@link RequiresSecret} annotations from the tool's
 * {@code execute()} method to build the secrets map</li>
 * <li>Delegates execution to the tool implementation</li>
 * </ol>
 */
@ApplicationScoped
public class ToolRegistry {

    private static final Logger log = Logger.getLogger(ToolRegistry.class);

    @Inject
    Instance<AgentTool> discoveredTools;


    @Inject
    ObjectMapper objectMapper;

    @Inject
    ProcessIsolationExecutor isolationExecutor;

    @Inject
    AuditService auditService;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    // Stateless — no CDI needed. Can be swapped to @Inject if custom policies are
    // required later.
    private final AutonomyPolicy autonomyPolicy = new AutonomyPolicy.Default();

    /**
     * Lists all discovered tool names.
     *
     * @return Unmodifiable list of tool names from CDI-discovered {@link AgentTool}
     *         beans.
     */
    public List<String> listTools() {
        return StreamSupport.stream(discoveredTools.spliterator(), false)
                .map(AgentTool::name)
                .toList();
    }

    /**
     * Finds a tool by name.
     *
     * @param toolName The unique tool name.
     * @return Optional containing the tool if found.
     */
    public Optional<AgentTool> findTool(String toolName) {
        return StreamSupport.stream(discoveredTools.spliterator(), false)
                .filter(tool -> tool.name().equals(toolName))
                .findFirst();
    }

    /**
     * Invokes a tool by name after verifying governance constraints.
     * <p>
     * Execution flow:
     * <ol>
     * <li>Lookup tool by name from CDI {@code Instance<AgentTool>}</li>
     * <li>Check
     * {@code AutonomyPolicy.isAllowed(autonomyLevel, tool.requiredOperation())}</li>
     * <li>If denied → throw {@link SecurityException}</li>
     * <li>Delegate to {@code tool.execute(params)}</li>
     * </ol>
     *
     * @param toolName The unique tool name.
     * @param params   Tool-specific parameters.
     * @return The tool execution result.
     * @throws SecurityException        If the operation is not allowed under the
     *                                  current autonomy level.
     * @throws IllegalArgumentException If the tool is not found.
     */
    public ToolResponse invokeTool(String toolName, Map<String, Object> params) {
        var tool = findTool(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolName));

        // Governance check
        var level = WorkspaceContext.autonomy();
        var requiredOp = tool.requiredOperation();

        if (!autonomyPolicy.isAllowed(level, requiredOp)) {
            // Step 4: Sprint 2B Approval Interception
            if (level == AutonomyLevel.SUPERVISED &&
                    (requiredOp == ToolOperation.NETWORK_OUTBOUND || requiredOp == ToolOperation.VAULT_ACCESS
                            || requiredOp == ToolOperation.EXECUTE_SHELL)) {

                log.infof("Tool '%s' requires approval at level %s (operation=%s)", toolName, level, requiredOp);
                try {
                    String paramsJson = objectMapper.writeValueAsString(params);
                    throw new RequiresApprovalException(toolName, paramsJson, WorkspaceContext.requireSessionId());
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to serialize tool parameters", e);
                }
            }

            log.warnf("Tool '%s' blocked: operation %s not allowed at autonomy level %s",
                    toolName, requiredOp, level);
            
            String userId = (String) MDC.get(MdcKeys.USER_ID);
            String agentRole = (String) MDC.get(MdcKeys.AGENT_ROLE);
            auditService.record(WorkspaceContext.requireSessionId(), userId, agentRole, 
                SecurityEventType.TOOL_BLOCKED, "tool=" + toolName + " op=" + requiredOp);

            throw new SecurityException(
                    "Operation " + requiredOp + " not permitted at autonomy level " + level);
        }

        log.infof("Invoking tool '%s' (operation=%s, level=%s)", toolName, requiredOp, level);

        if (tool.requiresSandbox()) {
            var requiredSecrets = extractRequiredSecrets(tool.getClass(), "sandboxCommand");
            var command = tool.sandboxCommand(params);
            var workspaceRoot = Path.of(workspaceRootDir, WorkspaceContext.requireSessionId());

            try {
                if (!Files.exists(workspaceRoot)) {
                    Files.createDirectories(workspaceRoot);
                }

                String output = isolationExecutor.executeSandboxed(
                        WorkspaceContext.requireSessionId(), workspaceRoot, command, requiredSecrets);
                
                String userId = (String) MDC.get(MdcKeys.USER_ID);
                String agentRole = (String) MDC.get(MdcKeys.AGENT_ROLE);
                auditService.record(WorkspaceContext.requireSessionId(), userId, agentRole, 
                    SecurityEventType.TOOL_INVOKED, "tool=" + toolName + " success=true");
                    
                return ToolResponse.success(output);
            } catch (SecurityException e) {
                return ToolResponse.failure(1, "Security violation: " + e.getMessage());
            } catch (Exception e) {
                // Friendly message for common GitHub / sandbox failures
                String raw = e.getMessage() != null ? e.getMessage() : "";

                String friendly;
                if (toolName.equalsIgnoreCase(GithubTool.NAME)
                        && raw.contains("Cannot run program \"gh\"")) {
                    friendly = """
                        GitHub integration is not fully configured on this server.
                        The GitHub CLI (`gh`) is not installed in this environment, \
                        so I cannot run GitHub commands for you.

                        You can run the equivalent `gh` command locally on your own machine instead.
                        """;
                } else if (toolName.equalsIgnoreCase(GithubTool.NAME)
                        && (raw.contains("Podman engine failure")
                            || raw.contains("unable to connect to Podman"))) {
                    friendly = """
                        The secure sandbox for GitHub commands is unavailable on this environment.
                        I cannot safely execute `gh` here right now.

                        Please try again later or run the GitHub command directly on your machine.
                        """;
                } else {
                    // default behaviour for other tools
                    friendly = "Tool '" + toolName + "' failed: " + raw;
                }

                log.errorf(e, "Tool '%s' execution failed: %s", toolName, raw);

                String userId = (String) MDC.get(MdcKeys.USER_ID);
                String agentRole = (String) MDC.get(MdcKeys.AGENT_ROLE);
                auditService.record(WorkspaceContext.requireSessionId(), userId, agentRole,
                    SecurityEventType.TOOL_INVOKED, "tool=" + toolName + " success=false");

                return ToolResponse.failure(1, friendly);
            }
        }

        log.infof("Invoking tool '%s' directly (sandbox opted out)", toolName);
        ToolResponse response = tool.execute(params);
        
        String userId = (String) MDC.get(MdcKeys.USER_ID);
        String agentRole = (String) MDC.get(MdcKeys.AGENT_ROLE);
        auditService.record(WorkspaceContext.requireSessionId(), userId, agentRole, 
            SecurityEventType.TOOL_INVOKED, "tool=" + toolName + " success=" + response.success());
            
        return response;
    }

    /**
     * Resumes execution of a pending tool from its serialized JSON parameters.
     * 
     * @param toolName   The unique tool name.
     * @param paramsJson The serialized JSON parameters.
     * @param sessionId  The workspace session ID.
     * @return The tool execution result.
     */
    public ToolResponse invokeTool(String toolName, String paramsJson, String sessionId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = objectMapper.readValue(paramsJson, Map.class);
            return invokeTool(toolName, params);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize tool parameters for " + toolName, e);
        }
    }

    /**
     * Reflectively reads {@link RequiresSecret} annotations from the named method
     * of a tool class to build the env-var → vault-key mapping.
     * <p>
     * Pass {@code "sandboxCommand"} for sandboxed tools (new default).
     * The old {@code "execute"} method name is no longer used.
     *
     * @param toolClass  The tool implementation class (can be a CDI proxy).
     * @param methodName The method to scan for @RequiresSecret annotations.
     * @return Immutable map of envVar → vaultKey.
     */
    public static Map<String, String> extractRequiredSecrets(
            Class<? extends AgentTool> toolClass, String methodName) {
        var secrets = new LinkedHashMap<String, String>();
        
        // THE FIX: Unwrap Quarkus CDI Proxies to read annotations from the true class
        Class<?> actualClass = toolClass;
        while (actualClass.getName().contains("_ClientProxy") || actualClass.getName().contains("_Subclass")) {
            actualClass = actualClass.getSuperclass();
        }

        try {
            Method method = actualClass.getMethod(methodName, Map.class);
            var container = method.getAnnotation(RequiresSecrets.class);
            if (container != null) {
                for (var rs : container.value()) {
                    secrets.put(rs.envVar(), rs.vaultKey());
                }
            }
            var single = method.getAnnotation(RequiresSecret.class);
            if (single != null && container == null) {
                secrets.put(single.envVar(), single.vaultKey());
            }

        } catch (NoSuchMethodException _) {
            log.warnf("Tool %s has no %s(Map) method — no secrets extracted",
                    actualClass.getName(), methodName);
        }
        return Collections.unmodifiableMap(secrets);
    }
}
