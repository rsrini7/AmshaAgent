package com.arcclaw.app;

import com.arcclaw.core.agent.ReasoningStep;
import com.arcclaw.core.observability.AgentObserver;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CLI-only observer — prints step-level events to stdout during
 * {@link AskCommand} execution.
 * <p>
 * <strong>Daemon-safe:</strong> The {@code enabled} flag is toggled
 * {@code true} only inside {@code AskCommand.run()}, preventing log
 * spam when running in daemon/REST mode via {@link ServeCommand}.
 */
@ApplicationScoped
public class CliAgentObserver implements AgentObserver {

    /**
     * Guard flag — only {@code true} while AskCommand is executing.
     * Volatile for cross-thread visibility (picocli thread vs. CDI threads).
     */
    public static volatile boolean enabled = false;

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        if (!enabled)
            return;
        System.out.printf("   💬 %d prompt / %d completion tokens%n",
                inputTokens, outputTokens);
    }

    @Override
    public void onStepComplete(String sessionId, String role, ReasoningStep step) {
        if (!enabled)
            return;
        System.out.printf("🤖 [%s] Action: %s%n", role, step.action());
        if (step.observation() != null && !step.observation().isBlank()) {
            String preview = step.observation().length() > 120
                    ? step.observation().substring(0, 120) + "..."
                    : step.observation();
            System.out.printf("   🔍 Observed: %s%n", preview);
        }
    }
}
