package com.arcclaw.core.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    // On Windows, Python is installed as 'python.exe' not 'python3.exe'.
    // 'python3' either doesn't exist or opens the Microsoft Store (exit 9009).
    static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @Inject
    ProcessIsolationExecutor isolationExecutor;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = ".data/workspaces")
    String workspaceRootDir;

    /**
     * Optional override: set to a full absolute path (e.g. C:\Python313\python.exe)
     * to bypass auto-detection. Useful in restricted CI environments.
     * Defaults to empty string = auto-detect by OS.
     */
    @ConfigProperty(name = "arcclaw.sandbox.python-executable")
    Optional<String> configuredPythonExe;

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
        // Manages own isolation via ProcessIsolationExecutor for multi-language support.
        return false;
    }

    public ToolCapability capability() {
        return ToolCapability.SANDBOX_EXEC;
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        try {

            // Defensive: LLMs occasionally echo JSON Schema structure back as args.
            // Unwrap {"properties": {"code":..., "language":...}} → {"code":..., "language":...}
            if (params.size() == 1 && params.containsKey("properties")
                    && params.get("properties") instanceof Map<?, ?> inner) {
                @SuppressWarnings("unchecked")
                Map<String, Object> unwrapped = (Map<String, Object>) inner;
                params = unwrapped;
            }

            String code = (String) params.get("code");
            String lang = (String) params.get("language");

            if (code == null) {
                return ToolResponse.failure(1, "Parameter 'code' is required.");
            }
            if (lang == null) {
                return ToolResponse.failure(1,
                        "Parameter 'language' is required. Allowed: " + ALLOWED_LANGUAGES +
                        ". Example: {\"code\":\"print('hi')\",\"language\":\"python\"}");
            }
            lang = lang.toLowerCase();

            if (!ALLOWED_LANGUAGES.contains(lang)) {
                return ToolResponse.failure(1, "Unsupported language: " + lang +
                        ". Allowed: " + ALLOWED_LANGUAGES);
            }

            List<String> commandList = new ArrayList<>(buildCommand(lang, code));

            // Append extra args if present
            Object argsObj = params.get("args");
            if (argsObj instanceof List<?> extraArgs) {
                for (Object arg : extraArgs) {
                    commandList.add(arg.toString());
                }
            }

            String sessionId = WorkspaceContext.requireSessionId();
            Path workspaceRoot = Path.of(workspaceRootDir, sessionId);

            Map<String, String> requiredSecrets = Map.of();
            Object secretsObj = params.get("secrets");
            if (secretsObj instanceof Map<?, ?> secretsMap) {
                requiredSecrets = objectMapper.convertValue(secretsMap,
                        objectMapper.getTypeFactory().constructMapType(
                                Map.class, String.class, String.class));
            }

            String output = isolationExecutor.executeSandboxed(
                    sessionId, workspaceRoot, commandList, requiredSecrets);

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
            throw e;
        } catch (Exception e) {
            log.errorf(e, "SandboxExecTool unexpected error for session %s",
                    WorkspaceContext.requireSessionId());
            return ToolResponse.failure(1, "Sandbox error: " + e.getMessage());
        }
    }

    /**
     * Builds the OS-aware command list for the given language and code snippet.
     * Package-private for unit testing.
     *
     * Python:  Linux/macOS → python3 -c <code>
     *          Windows     → python  -c <code>  (python3.exe does not exist on Windows;
     *                                             typing it triggers the MS Store redirect
     *                                             and exits with code 9009)
     * Bash:    Kept as-is on all platforms. On Windows, bash is available via Git Bash
     *          or WSL; users without either should use 'python' or 'node' instead.
     * Node:    node -e <code> (same on all platforms).
     */
    List<String> buildCommand(String lang, String code) {
        return switch (lang) {
            case "python", "python3" -> {
                String exe = resolvePythonExecutable();
                yield List.of(exe, "-c", code);
            }
            case "bash" -> List.of("bash", "-c", code);
            case "node" -> List.of("node", "-e", code);
            default -> throw new IllegalStateException(
                    "Allowed-languages check passed for unexpected lang: " + lang);
        };
    }

    /**
     * Resolves the Python executable name.
     * Priority: explicit config → OS detection.
     * Package-private for unit testing.
     */
    String resolvePythonExecutable() {
         return configuredPythonExe
            .filter(exe -> !exe.isBlank())
            .orElse(IS_WINDOWS ? "python" : "python3");
    }
}