package com.arcclaw.core.tools;

import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyPolicy;
import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.RequiresSecrets;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.ToolOperation;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.StreamSupport;

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
    WorkspaceContext workspaceContext;

    @Inject
    ObjectMapper objectMapper;

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
        var level = workspaceContext.getAutonomyLevel();
        var requiredOp = tool.requiredOperation();

        if (!autonomyPolicy.isAllowed(level, requiredOp)) {
            // Step 4: Sprint 2B Approval Interception
            if (level == AutonomyLevel.SUPERVISED &&
                    (requiredOp == ToolOperation.NETWORK_OUTBOUND || requiredOp == ToolOperation.VAULT_ACCESS
                            || requiredOp == ToolOperation.EXECUTE_SHELL)) {

                log.infof("Tool '%s' requires approval at level %s (operation=%s)", toolName, level, requiredOp);
                try {
                    String paramsJson = objectMapper.writeValueAsString(params);
                    throw new RequiresApprovalException(toolName, paramsJson, "current");
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new RuntimeException("Failed to serialize tool parameters", e);
                }
            }

            log.warnf("Tool '%s' blocked: operation %s not allowed at autonomy level %s",
                    toolName, requiredOp, level);
            throw new SecurityException(
                    "Operation " + requiredOp + " not permitted at autonomy level " + level);
        }

        log.infof("Invoking tool '%s' (operation=%s, level=%s)", toolName, requiredOp, level);
        return tool.execute(params);
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
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize tool parameters for " + toolName, e);
        }
    }

    /**
     * Reflectively reads {@link RequiresSecret} annotations from a tool's
     * {@code execute(Map)} method to build the env-var → vault-key mapping.
     * <p>
     * Used by tools that delegate to {@code ProcessIsolationExecutor} and need
     * to pass secrets into the container's ephemeral env-file.
     *
     * @param toolClass The tool implementation class.
     * @return Map of envVar → vaultKey from {@code @RequiresSecret} annotations.
     */
    public static Map<String, String> extractRequiredSecrets(Class<? extends AgentTool> toolClass) {
        var secrets = new LinkedHashMap<String, String>();
        try {
            Method executeMethod = toolClass.getMethod("execute", Map.class);

            // Check for @RequiresSecrets container annotation first
            var container = executeMethod.getAnnotation(RequiresSecrets.class);
            if (container != null) {
                for (var rs : container.value()) {
                    secrets.put(rs.envVar(), rs.vaultKey());
                }
            }

            // Check for single @RequiresSecret (not inside container)
            var single = executeMethod.getAnnotation(RequiresSecret.class);
            if (single != null && container == null) {
                secrets.put(single.envVar(), single.vaultKey());
            }
        } catch (NoSuchMethodException e) {
            log.warnf("Tool %s has no execute(Map) method — no secrets extracted", toolClass.getName());
        }
        return Collections.unmodifiableMap(secrets);
    }
}
