package com.arcclaw.core.tools;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.CapabilityRegistry;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.governance.RequiresApprovalException;
import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import com.arcclaw.core.sandbox.SandboxResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.AutonomyPolicy;
import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.RequiresSecrets;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.util.PathSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * CDI-based tool registry with governance enforcement.
 * <p>
 * Discovers all {@link AgentTool} implementations via {@code Instance<AgentTool>}
 * at runtime. Before invoking any tool, the registry:
 * <ol>
 * <li>Enforces role-capability hard block (schema filter is soft — LLM can hallucinate past it)</li>
 * <li>Verifies autonomy governance via {@link AutonomyPolicy}</li>
 * <li>Reflectively reads {@link RequiresSecret} annotations from the tool's
 *     {@code execute()} method to build the secrets map</li>
 * <li>Delegates execution to the tool implementation</li>
 * </ol>
 * <p>
 * Audit userId/agentRole are sourced from {@link WorkspaceContext}/{@link WorkspaceScopeHolder}
 * ScopedValues — NOT MDC. MDC is ThreadLocal and dies at virtual thread boundaries;
 * ScopedValues are propagated correctly across Quarkus virtual threads.
 */
@ApplicationScoped
public class ToolRegistry {

    private static final Logger log = Logger.getLogger(ToolRegistry.class);

    @Inject Instance<AgentTool> discoveredTools;
    @Inject ObjectMapper objectMapper;
    @Inject ProcessIsolationExecutor isolationExecutor;
    @Inject AuditService auditService;
    @Inject CapabilityRegistry capabilityRegistry;
    @Inject RoleRegistry roleRegistry;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    // Stateless — no CDI needed. Can be swapped to @Inject if custom policies are
    // required later.
    private final AutonomyPolicy autonomyPolicy = new AutonomyPolicy.Default();

    private Map<String, Map<String, String>> secretsCache;

    @PostConstruct
    void initialize() {
        log.info("ToolRegistry: Initializing and discovering tool capabilities...");
        doInitialize();
    }

    void onStart(@Observes StartupEvent ev) {
        // Accessing the bean triggers @PostConstruct if it hasn't run yet
        log.info("ToolRegistry: Eager startup verification complete.");
    }

    private void doInitialize() {
        Map<String, Map<String, String>> cache = new HashMap<>();
        for (AgentTool tool : discoveredTools) {
            String name = tool.name();
            
            // 1. Build secrets cache
            if (tool.isEnabled()) {
                cache.put(name, extractRequiredSecrets(tool.getClass(), "sandboxCommand"));
            }

            // 2. Discover metadata-driven capabilities
            var meta = tool.getClass().getAnnotation(ToolMetadata.class);
            if (meta == null) {
                Class<?> current = tool.getClass();
                while (current != null && current != Object.class) {
                    meta = current.getAnnotation(ToolMetadata.class);
                    if (meta != null) break;
                    current = current.getSuperclass();
                }
            }

            if (meta != null) {
                log.infof("Discovered dynamic tool capability: %s (granting to: %s)",
                          meta.capabilityId(), String.join(", ", meta.defaultRoles()));
                for (String roleId : meta.defaultRoles()) {
                    capabilityRegistry.grant(roleId, name);
                }
            }
        }
        this.secretsCache = Collections.unmodifiableMap(cache);
    }

    // Path safety 

    /**
     * Converts a session ID to a safe directory name for all OS platforms.
     *
     * Windows BUG Colons (':') are illegal in Windows path components —
     * they are reserved as drive letter separators (e.g. C:\).
     * Delegate session IDs use the format "parentId:delegate:agentname:timestamp", which
     * causes InvalidPathException on every sandbox tool call inside a sub-agent
     * on Windows. Example that crashed:
     *   data/workspaces\c77013fc-...-2ad15af79f03:delegate:researcher:38373837338
     *
     * Fix: replace ':' → '_' for the directory name only.
     * The session ID string itself is unchanged everywhere else
     * (DB keys, chat memory, audit logs, CDI scope).
     *
     * Linux/Mac: ':' is legal in filenames, but normalizing it here is safe and
     * makes workspace dirs portable across platforms.
     */
    static String toSafeDirName(String sessionId) {
        return PathSanitizer.sanitizeForFilesystem(sessionId);
    }

    // Tool listing / lookup 
    public List<String> listTools() {
        return StreamSupport.stream(discoveredTools.spliterator(), false)
                .filter(AgentTool::isEnabled)
                .map(AgentTool::name)
                .toList();
    }

    /**
     * Returns a comma-separated list of enabled tool names.
     */
    public String registeredToolNames() {
        return listTools().stream().collect(Collectors.joining(", "));
    }

    /**
     * Finds a tool by name.
     *
     * @param toolName The unique tool name.
     * @return Optional containing the tool if found.
     */
    public Optional<AgentTool> findTool(String toolName) {
        return StreamSupport.stream(discoveredTools.spliterator(), false)
                .filter(AgentTool::isEnabled)
                .filter(tool -> tool.name().equals(toolName))
                .findFirst();
    }

    // ── Invocation 

    /**
     * Invokes a tool by name after verifying governance constraints.
     * <p>
     * Execution flow:
     * <ol>
     * <li>Lookup tool by name from CDI {@code Instance<AgentTool>}</li>
     * <li>Role-capability hard block — role must explicitly own the capability</li>
     * <li>Check {@code AutonomyPolicy.isAllowed(autonomyLevel, tool.requiredOperation())}</li>
     * <li>If denied → throw {@link SecurityException} or {@link RequiresApprovalException}</li>
     * <li>Delegate to {@code tool.execute(params)} or sandbox path</li>
     * </ol>
     *
     * @param toolName The unique tool name.
     * @param params   Tool-specific parameters.
     * @return The tool execution result.
     * @throws SecurityException        If the operation is not allowed under the current autonomy level.
     * @throws IllegalArgumentException If the tool is not found.
     */
    public ToolResponse invokeTool(String toolName, Map<String, Object> params) {
        var tool = findTool(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolName));

        // Role-capability hard block 
        // Schema filter is soft (LLM can hallucinate past it).
        // This is the hard enforcement layer — role must explicitly own the capability.
        String agentRole = WorkspaceContext.agentRole();
        if (agentRole != null) {
            if (!AgentRoleEnum.isKnownRole(agentRole)) {
                log.warnf("SECURITY: Unknown agentRole=%s — blocking tool=%s", agentRole, toolName);
                return ToolResponse.failure(403, "Unknown agent role: " + agentRole);
            }
            AgentRoleEnum role = AgentRoleEnum.fromString(agentRole);
            Set<String> allowedTools = roleRegistry.permittedTools(role);
            boolean roleAllowed = allowedTools.contains(toolName);
            if (!roleAllowed) {
                log.warnf("SECURITY: role=%s attempted tool=%s — not in capability set. Blocked.",
                        agentRole, toolName);
                auditService.record(
                        WorkspaceContext.requireSessionId(), resolveUserId(), agentRole,
                        SecurityEventType.TOOL_BLOCKED,
                        "tool=" + toolName + " reason=ROLE_CAPABILITY_MISMATCH role=" + agentRole);
                return ToolResponse.failure(403,
                        "Tool '" + toolName + "' is not available for role '" + agentRole + "'. "
                        + "This action requires a different agent role.");
            }
        }

        // Autonomy governance check
        var level      = WorkspaceContext.autonomy();
        var requiredOp = tool.requiredOperation();

        if (!autonomyPolicy.isAllowed(level, requiredOp)) {
            if (level == AutonomyLevel.SUPERVISED &&
                    (requiredOp == ToolOperation.NETWORK_OUTBOUND
                            || requiredOp == ToolOperation.VAULT_ACCESS
                            || requiredOp == ToolOperation.EXECUTE_SHELL)) {
                log.infof("Tool '%s' requires approval at level %s (operation=%s)",
                          toolName, level, requiredOp);
                try {
                    String paramsJson = objectMapper.writeValueAsString(params);
                    throw new RequiresApprovalException(
                        toolName, paramsJson, WorkspaceContext.requireSessionId());
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to serialize tool parameters", e);
                }
            }

            log.warnf("Tool '%s' blocked: operation %s not allowed at autonomy level %s",
                    toolName, requiredOp, level);
            auditService.record(
                    WorkspaceContext.requireSessionId(), resolveUserId(),
                    WorkspaceContext.agentRole(),
                    SecurityEventType.TOOL_BLOCKED,
                    "tool=" + toolName + " op=" + requiredOp);
            throw new SecurityException(
                    "Operation " + requiredOp + " not permitted at autonomy level " + level);
        }

        log.infof("Invoking tool '%s' (operation=%s, level=%s)", toolName, requiredOp, level);

        // NULL PARAM STRIP
        // Some LLMs send optional fields as explicit null (e.g. "ref": null)
        // instead of omitting them. This can break downstream validators or 
        // cause 400 errors from strict APIs. Strip them here for all tools.
        Map<String, Object> sanitizedParams = params.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // Sandbox path 
        if (tool.requiresSandbox()) {
            recordToolAudit(toolName);
            var requiredSecrets = extractRequiredSecrets(tool);

            // Use toSafeDirName() to strip colons from the session ID.
            // Windows treats ':' as a drive separator — delegate sub-agent session IDs
            // like "parentId:d:researcher:cf747207" crash Path.of() on Windows with
            // InvalidPathException. The safe dir name is only used for the filesystem
            // path; the original session ID is preserved everywhere else.
            String safeDir = toSafeDirName(WorkspaceContext.requireSessionId());
            var workspaceRoot = Path.of(workspaceRootDir, safeDir);

            try {
                if (!Files.exists(workspaceRoot)) {
                    Files.createDirectories(workspaceRoot);
                }

                tool.prepareWorkspace(workspaceRoot, sanitizedParams);

                var command = tool.sandboxCommand(sanitizedParams);

                SandboxResult result = isolationExecutor.executeSandboxed(
                        WorkspaceContext.requireSessionId(), workspaceRoot,
                        command, requiredSecrets);

                if (result.isInfraFailure()) {
                    return ToolResponse.infraFailure(result.reason());
                }

                String postProcessedOutput = tool.postProcessOutput(
                    result.output(), sanitizedParams);

                return ToolResponse.success(postProcessedOutput);

            } catch (IllegalArgumentException e) {
                // Allowlist / guardrail violations from sandboxCommand() — tell the LLM clearly
                log.warnf("Tool '%s' parameter validation failed: %s", toolName, e.getMessage());
                return ToolResponse.failure(1, e.getMessage());

            } catch (SecurityException e) {
                return ToolResponse.failure(1, "Security violation: " + e.getMessage());

            } catch (Exception e) {
                String raw = e.getMessage() != null ? e.getMessage() : "";
                String friendly;
                if (toolName.equalsIgnoreCase(ToolCapability.GITHUB.toolName())
                        && raw.contains("Cannot run program \"gh\"")) {
                    friendly = "GitHub integration is not available in this environment. "
                            + "The `gh` CLI is not installed. "
                            + "Please run the equivalent `gh` command locally.";
                } else {
                    friendly = "EXECUTION_FAILED: The action was NOT performed. No output was produced. " +
                               "Reason: " + raw + " " +
                               "Do NOT simulate or guess output. Tell the user exactly what failed.";
                }
                log.errorf("Tool '%s' execution failed: %s", toolName, raw);
                log.debugf(e, "Full stacktrace for tool '%s' failure in session %s",
                           toolName, WorkspaceContext.requireSessionId());
                return ToolResponse.failure(1, friendly);
            }
        }

        // Direct (non-sandbox) path
        log.infof("Invoking tool '%s' directly (sandbox opted out)", toolName);
        recordToolAudit(toolName);
        ToolResponse response = tool.execute(sanitizedParams);
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
            throw new IllegalArgumentException(
                "Failed to deserialize tool parameters for " + toolName, e);
        }
    }

    // Secrets
    /**
     * Extracts required secrets either from cache (eagerly populated at startup) or directly via reflection.
     */
     public Map<String, String> extractRequiredSecrets(AgentTool tool) {
        Map<String, String> cached = secretsCache.get(tool.name());
        if (cached != null) return cached;
        return extractRequiredSecrets(tool.getClass(), "sandboxCommand");
    }

    // Audit / internal 

    /**
     * Records a TOOL_INVOKED audit event using ScopedValues — not MDC.
     * <p>
     * MDC is ThreadLocal and dies at virtual thread boundaries. WorkspaceContext
     * and WorkspaceScopeHolder use ScopedValues which are correctly propagated
     * across Quarkus virtual threads.
     */
    private void recordToolAudit(String toolName) {
        auditService.record(
                WorkspaceContext.requireSessionId(), resolveUserId(),
                WorkspaceContext.agentRole(),
                SecurityEventType.TOOL_INVOKED,
                "tool=" + toolName);
    }

    /**
     * Resolves the userId from the WorkspaceScopeHolder ScopedValue.
     * Falls back to {@code "internal"} if the scope is not bound
     * (e.g., background cron execution without an API key context).
     */
    private String resolveUserId() {
        return WorkspaceScopeHolder.USER_ID.isBound()
                ? WorkspaceScopeHolder.USER_ID.get()
                : "internal";
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

        // Unwrap Quarkus CDI proxies to read annotations from the true class
        Class<?> actualClass = toolClass;
        while (actualClass.getName().contains("_ClientProxy")
                || actualClass.getName().contains("_Subclass")) {
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

    public record ToolDescriptor(String name, String description, String inputSchema) {}

    public List<ToolDescriptor> listToolDescriptors() {
        return StreamSupport.stream(discoveredTools.spliterator(), false)
                .filter(AgentTool::isEnabled)
                .map(tool -> {
                    String schema = null;
                    try {
                        var ps = tool.parametersSchema();
                        var schemaMap = schemaToMap(ps);   // ← convert first
                        schema = schemaMap != null
                            ? objectMapper.writeValueAsString(schemaMap)
                            : null;
                    } catch (Exception e) {
                        log.warnf("Could not serialize schema for tool %s: %s",
                            tool.name(), e.getMessage());  // ← now visible
                    }
                    return new ToolDescriptor(tool.name(), tool.description(), schema);
                })
                .toList();
    }

    private Map<String, Object> schemaToMap(JsonObjectSchema schema) {
        if (schema == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        if (schema.properties() != null && !schema.properties().isEmpty()) {
            Map<String, Object> props = new LinkedHashMap<>();
            schema.properties().forEach((k, v) -> props.put(k, elementToMap(v)));
            result.put("properties", props);
        }
        if (schema.required() != null && !schema.required().isEmpty()) {
            result.put("required", schema.required());
        }
        if (schema.description() != null) {
            result.put("description", schema.description());
        }
        return result;
    }

    private Object elementToMap(JsonSchemaElement el) {
        if (el == null) return null;
        return switch (el) {
            case JsonObjectSchema o -> schemaToMap(o);
            case JsonStringSchema s -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "string");
                if (s.description() != null) m.put("description", s.description());
                yield m;
            }
            case JsonIntegerSchema i -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "integer");
                if (i.description() != null) m.put("description", i.description());
                yield m;
            }
            case JsonBooleanSchema b -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "boolean");
                if (b.description() != null) m.put("description", b.description());
                yield m;
            }
            case JsonArraySchema a -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "array");
                if (a.items() != null) m.put("items", elementToMap(a.items()));
                if (a.description() != null) m.put("description", a.description());
                yield m;
            }
            case JsonEnumSchema e -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("type", "string");
                m.put("enum", e.enumValues());
                if (e.description() != null) m.put("description", e.description());
                yield m;
            }
            default -> Map.of("type", "any");
        };
    }
}
