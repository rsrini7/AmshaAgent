package com.arcclaw.app.channel.telegram;

import dev.langchain4j.model.chat.ChatLanguageModel;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class IntentClassifierService {

    @Inject
    @Identifier("intent-classifier")
    ChatLanguageModel classifierModel;

    @Inject
    org.eclipse.microprofile.context.ManagedExecutor managedExecutor;

    private static final Logger Log = Logger.getLogger(IntentClassifierService.class);

    private static final Set<String> TOOL_CAPABLE_ROLES =
        Set.of("coordinator", "researcher", "saraswati");

    private static final Pattern DESTRUCTIVE_PATTERN = Pattern.compile(
        "(?i)\\b(delete|purge|drop|rm\\s*-rf|chmod|truncate|wipe|erase|destroy|archive|rename)\\b"
    );

    private static final String CLASSIFIER_PROMPT = """
            You are a router. Classify the following user message into exactly
            one of these agent roles. Reply with ONLY the role name, nothing else.

            Roles:
            - brahma: CREATING new code, building new features, writing new scripts
            - shiva: FIXING existing code, diagnosing bugs, analyzing stack traces
            - saraswati: research, facts, finding information, GitHub actions
            - coordinator: SCHEDULING tasks, planning, general questions, coordination, anything else

            PRIORITY ROUTING RULES:
            1. SCHEDULING OVERRIDES ALL CONTEXT: If the user requests to schedule a task, set a reminder, or delay execution (e.g., "after 1 min", "schedule", "remind me", "every day"), you MUST route the request to 'coordinator'. Do this EVEN IF the request mentions GitHub, code, or research. The 'coordinator' is the only persona authorized to schedule future tasks.

            CRITICAL GUARDRAILS:
            1. (UNLESS SCHEDULING) If the user mentions GitHub, gists, or repositories to LIST, FETCH, or VIEW existing content -> saraswati.
            2. (UNLESS SCHEDULING) If the user mentions GitHub, gists, or repositories to CREATE, BUILD, or GENERATE new content -> brahma.
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
                String sanitizedRole = sanitize(raw.trim().toLowerCase());
                return applyDestructiveSafetyOverride(sanitizedRole, userMessage);
            }, managedExecutor).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.warnf("Intent classifier failed for '%s': %s — defaulting to coordinator",
                    userMessage.length() > 40 ? userMessage.substring(0, 40) + "..." : userMessage,
                    e.getMessage());
            return "coordinator";
        }
    }

    private String applyDestructiveSafetyOverride(String role, String userMessage) {
        if (TOOL_CAPABLE_ROLES.contains(role) &&
            DESTRUCTIVE_PATTERN.matcher(userMessage).find()) {
            Log.warnf("Destructive vocabulary detected in message routed to '%s'. " +
                "Overriding to 'coordinator' with no-tool mode.", role);
            return "coordinator";
        }
        return role;
    }

    private String sanitize(String raw) {
        String lowerRaw = raw.toLowerCase();
        // Translate persona to actual functional duty
        if (lowerRaw.contains("shiva") || lowerRaw.contains("debugger")) return "debugger";
        if (lowerRaw.contains("brahma") || lowerRaw.contains("coder")) return "coder";
        if (lowerRaw.contains("saraswati") || lowerRaw.contains("researcher")) return "researcher";
        return "coordinator";
    }
}