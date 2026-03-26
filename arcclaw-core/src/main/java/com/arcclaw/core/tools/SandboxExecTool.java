package com.arcclaw.core.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import com.arcclaw.core.sandbox.ToolExecutionException;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SandboxExecTool implements AgentTool {

    private static final Logger log = Logger.getLogger(SandboxExecTool.class);
    private static final int MAX_OUTPUT_CHARS = 8192;
    private static final String TRUNCATION_SUFFIX = "[output truncated at 8192 chars]";
    private static final Set<String> ALLOWED_LANGUAGES =
            Set.of("python", "python3", "bash", "node");

    @Inject
    ProcessIsolationExecutor isolationExecutor;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = ".data/workspaces")
    String workspaceRootDir;

    @Override
    public String name() {
        return ToolCapability.SANDBOX_EXEC.toolName();
    }

    @Override
    public String description() {
        return ToolCapability.SANDBOX_EXEC.description();
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.EXECUTE_SHELL;
    }

    @Override
    public boolean requiresSandbox() {
        // We manage our own isolation via direct ProcessIsolationExecutor injection
        // to support multi-language command building.
        return false;
    }

    /**
     * Helper for tests or direct JSON string execution if needed.
     */
    public ToolCapability capability() {
        return ToolCapability.SANDBOX_EXEC;
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        try {
            String code = (String) params.get("code");
            String lang = (String) params.get("language");

            if (code == null) {
                return ToolResponse.failure(1, "Parameter 'code' is required.");
            }
            if (lang == null) {
                return ToolResponse.failure(1, "Parameter 'language' is required.");
            }
            lang = lang.toLowerCase();

            if (!ALLOWED_LANGUAGES.contains(lang)) {
                return ToolResponse.failure(1, "Unsupported language: " + lang +
                        ". Allowed: " + ALLOWED_LANGUAGES);
            }

            // Build Command List
            List<String> commandList = new java.util.ArrayList<>(switch (lang) {
                case "python", "python3" -> List.of("python3", "-c", code);
                case "bash" -> List.of("bash", "-c", code);
                case "node" -> List.of("node", "-e", code);
                default -> throw new IllegalStateException("Allowed languages check failed");
            });

            // Append extra args if present
            Object argsObj = params.get("args");
            if (argsObj instanceof List<?> extraArgs) {
                for (Object arg : extraArgs) {
                    commandList.add(arg.toString());
                }
            }

            // Resolve Workspace
            String sessionId = WorkspaceContext.requireSessionId();
            Path workspaceRoot = Path.of(workspaceRootDir, sessionId);

            // Resolve Secrets
            Map<String, String> requiredSecrets = Map.of();
            Object secretsObj = params.get("secrets");
            if (secretsObj instanceof Map<?, ?> secretsMap) {
                requiredSecrets = objectMapper.convertValue(secretsMap, 
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            }

            // Execute
            String output = isolationExecutor.executeSandboxed(
                    sessionId, workspaceRoot, commandList, requiredSecrets);

            // Truncate
            if (output != null && output.length() > MAX_OUTPUT_CHARS) {
                output = output.substring(0, MAX_OUTPUT_CHARS) + TRUNCATION_SUFFIX;
            }

            return ToolResponse.success(output != null ? output : "");

        } catch (ToolExecutionException e) {
            return ToolResponse.failure(e.exitCode(), "Execution failed: " + e.getMessage());
        } catch (TimeoutException | InterruptedException e) {
            return ToolResponse.failure(1, "Sandbox execution timed out.");
        } catch (SecurityException e) {
            return ToolResponse.failure(1, "Sandbox security violation: " + e.getMessage());
        } catch (BudgetExceededException e) {
            throw e; // RETHROW
        } catch (Exception e) {
            log.errorf(e, "SandboxExecTool unexpected error for session %s", WorkspaceContext.requireSessionId());
            return ToolResponse.failure(1, "Sandbox error: " + e.getMessage());
        }
    }
}
