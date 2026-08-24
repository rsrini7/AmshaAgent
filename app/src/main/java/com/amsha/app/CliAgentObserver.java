package com.amsha.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.observability.AgentObserver;

import org.jboss.logging.Logger;

/**
 * CLI-only observer - prints step-level events to stdout during
 * {@link AskCommand} execution.
 * <p>
 * <strong>Daemon-safe:</strong> The {@code verbose} flag is bound only inside
 * {@code AskCommand.run()}, preventing CLI progress output when running in
 * daemon/REST mode via {@link ServeCommand}.
 */
@ApplicationScoped
public class CliAgentObserver implements AgentObserver {

    @Inject
    Logger log;

    /**
     * Guard flag - bound to the {@code --verbose} value while AskCommand executes.
     * Use ScopedValue to prevent leakage across concurrent REST requests.
     */
    public static final ScopedValue<Boolean> VERBOSE = ScopedValue.newInstance();

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        if (!VERBOSE.isBound() || !VERBOSE.get()) return;
        log.infof("CLI agent tokens for session %s: prompt=%d completion=%d", sessionId, inputTokens, outputTokens);
        System.out.printf("   💬 %d prompt / %d completion tokens%n", inputTokens, outputTokens);
    }

    @Override
    public void onStepComplete(String sessionId, String role, ReasoningStep step) {
        if (!VERBOSE.isBound() || !VERBOSE.get()) return;
        log.infof("CLI agent step for session %s role=%s action=%s", sessionId, role, step.action());
        System.out.printf("🤖 [%s] Action: %s%n", role, step.action());

        if (step.observation() != null && !step.observation().isBlank()) {
            String preview = step.observation().length() > 120
                    ? step.observation().substring(0, 120) + "..."
                    : step.observation();
            log.infof("CLI agent observation for session %s role=%s: %s", sessionId, role, preview);
            System.out.printf("   🔍 Observed: %s%n", preview);
        }
    }
}
