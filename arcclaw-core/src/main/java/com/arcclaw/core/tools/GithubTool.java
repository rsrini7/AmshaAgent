package com.arcclaw.core.tools;

import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.ToolOperation;
import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reference tool implementation: GitHub CLI operations via sandboxed execution.
 * <p>
 * Supports 'repo' and 'gist' subcommands.
 */
@ApplicationScoped
public class GithubTool implements AgentTool {

    @Override
    public String name() {
        return "github";
    }

    @Override
    public String description() {
        return "Interact with GitHub using the gh CLI. " +
                "Parameters: " +
                "1. 'action': 'repo' or 'gist'. " +
                "2. 'command': 'view' or 'list'. " +
                "3. 'target': (Optional) The target owner or owner/repo path.";
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.NETWORK_OUTBOUND;
    }

    @Override
    public boolean requiresSandbox() {
        return true;
    }

    @Override
    @RequiresSecret(envVar = "GH_TOKEN", vaultKey = "GITHUB_TOKEN")
    public List<String> sandboxCommand(Map<String, Object> params) {
        String action = (String) params.getOrDefault("action", "repo");
        String command = (String) params.getOrDefault("command", "view");
        String target = (String) params.get("target");

        // SMART GUARDRAILS: Teach the LLM how to use the CLI correctly if it makes a mistake
        if ("repo".equals(action) && target != null) {
            if ("view".equals(command) && !target.contains("/")) {
                throw new IllegalArgumentException(
                    "Invalid target '" + target + "'. To 'view' a repository, you MUST provide the full 'owner/repo' format. " +
                    "Ask the user for the repository name if they only provided the owner."
                );
            }
            if ("list".equals(command) && target.contains("/")) {
                throw new IllegalArgumentException(
                    "Invalid target '" + target + "'. To 'list' repositories, you MUST provide ONLY the owner name (e.g., '" + target.split("/")[0] + "'). " +
                    "If you meant to view details of a specific repository, change the command to 'view'."
                );
            }
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("gh");
        cmd.add(action);
        cmd.add(command);

        if (target != null && !target.isBlank()) {
            cmd.add(target);
        }
        
        return cmd;
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        throw new UnsupportedOperationException("GithubTool requires sandbox execution");
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("action",  "Action to perform: 'repo' or 'gist'")
            .addStringProperty("command", "Command to run: 'view' or 'list'")
            .addStringProperty("target",  "For 'view', use 'owner/repo' format. For 'list', use ONLY the 'owner' name.")
            .required("action", "command")
            .build();
    }
}