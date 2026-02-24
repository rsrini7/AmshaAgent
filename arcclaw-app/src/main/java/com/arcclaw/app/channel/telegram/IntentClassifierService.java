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
            - brahma: CREATING new code, building new features, writing new scripts
            - shiva: FIXING existing code, diagnosing bugs, analyzing stack traces
            - saraswati: research, facts, finding information, GitHub actions
            - coordinator: planning, general questions, coordination, anything else

            CRITICAL GUARDRAILS:
            1. If the user mentions GitHub, gists, or repositories to LIST, FETCH, or VIEW existing content -> saraswati.
            2. If the user mentions GitHub, gists, or repositories to CREATE, BUILD, or GENERATE new content -> brahma. GitHub context does NOT override creation intent.
            3. Only output 'shiva' if the user provides actual error text, stack traces, or explicitly says "fix", "broken", "not working" with technical detail. Negative words like "no", "none", or "missing" alone are NOT debugging signals.
            4. "Hi", "Hello", greetings, or vague messages -> coordinator only.

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
                    userMessage.length() > 40 ? userMessage.substring(0, 40) + "..." : userMessage,
                    e.getMessage());
            return "coordinator";
        }
    }

    private String sanitize(String raw) {
        if (raw.contains("shiva") || raw.contains("debugger")) return "debugger";
        if (raw.contains("brahma") || raw.contains("coder")) return "coder";
        if (raw.contains("saraswati") || raw.contains("researcher")) return "researcher";
        return "coordinator";
    }
}