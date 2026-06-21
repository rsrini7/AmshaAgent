package com.arcclaw.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.File;

import com.arcclaw.ArcClawInfo;
import com.arcclaw.core.tools.ToolRegistry;

/**
 * Component A1 - SystemEnvironmentContextEnricher.
 * Injects host environment metadata into the agent's system prompt.
 *
 * This implementation is stateless and idempotent.
 */
@ApplicationScoped
public class SystemEnvironmentContextEnricher implements ContextEnricher {

    @Inject
    ToolRegistry toolRegistry;

    @Override
    public String enrich(String sessionId, String userId, String goal, String basePrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Host Environment Context\n");
        sb.append("- **Working Directory**: `")
                .append(System.getProperty("user.dir"))
                .append("`\n");

        boolean gitDetected = new File(System.getProperty("user.dir"), ".git").exists();
        sb.append("- **Git Repository Detected**: ")
                .append(gitDetected ? "Yes" : "No")
                .append("\n");

        sb.append("- **OS**: ")
                .append(System.getProperty("os.name"))
                .append(" (")
                .append(System.getProperty("os.arch"))
                .append(")\n");

        sb.append("- **Java Version**: ")
                .append(System.getProperty("java.version"))
                .append("\n");

        sb.append("- **ArcClaw Version**: ").append(ArcClawInfo.VERSION).append("\n");

        String tools = toolRegistry.registeredToolNames();
        sb.append("- **Registered Agent Tools**: ")
                .append(tools.isEmpty() ? "none" : tools)
                .append("\n");

        return basePrompt + "\n" + sb.toString();
    }
}
