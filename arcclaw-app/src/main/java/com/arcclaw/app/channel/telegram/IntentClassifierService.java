package com.arcclaw.app.channel.telegram;

import dev.langchain4j.model.chat.ChatLanguageModel;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class IntentClassifierService {

    @Inject
    @Identifier("intent-classifier")
    ChatLanguageModel classifierModel;

    @Inject
    org.eclipse.microprofile.context.ManagedExecutor managedExecutor;

    private static final Logger Log = Logger.getLogger(IntentClassifierService.class);

    private static final String CLASSIFIER_PROMPT = """
            You are a router. Classify the following user message into exactly
            one of these agent roles. Reply with ONLY the role name, nothing else.

            Roles:
            - brahma: CREATING new code, building new features, writing new scripts from scratch, implementing new functionality
            - shiva: FIXING existing code, diagnosing bugs, analyzing stack traces, explaining error messages, code review, finding issues in existing code
            - saraswati: research, facts, "who is", "what is", finding information
            - coordinator: planning, general questions, coordination, anything else

            User message: "%s"

            Reply with one word only: brahma, shiva, saraswati, or coordinator
            """;

    public String classify(String userMessage) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                String raw = classifierModel.chat(
                        CLASSIFIER_PROMPT.formatted(userMessage));
                return sanitize(raw.trim().toLowerCase());
            }, managedExecutor).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.warnf("Intent classifier failed for '%s': %s — defaulting to coordinator",
                    userMessage.length() > 40
                            ? userMessage.substring(0, 40) + "..."
                            : userMessage,
                    e.getMessage());
            return "coordinator";
        }
    }

    private String sanitize(String raw) {
        String lower = raw.toLowerCase();
        // Translate persona to actual functional duty
        if (lower.contains("shiva") || lower.contains("debugger")) return "debugger";
        if (lower.contains("brahma") || lower.contains("coder")) return "coder";
        if (lower.contains("saraswati") || lower.contains("researcher")) return "researcher";
        return "coordinator";
    }
}
