package com.arcclaw.app.routing;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import com.arcclaw.app.security.DestructiveVocabularyFilter;
import com.arcclaw.channel.api.IntentClassifier;

import dev.langchain4j.model.chat.ChatLanguageModel;
import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@Unremovable
public class IntentClassifierService implements IntentClassifier {

    @Inject
    @Identifier("intent-classifier")
    ChatLanguageModel classifierModel;

    @Inject
    org.eclipse.microprofile.context.ManagedExecutor managedExecutor;

    @Inject
    DestructiveVocabularyFilter destructiveFilter;

    private static final Logger Log = Logger.getLogger(IntentClassifierService.class);

    private String dynamicClassifierPrompt;

    @jakarta.annotation.PostConstruct
    void init() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a router. Classify the following user message into exactly
                one of these agent roles. Reply with ONLY the role name, nothing else.

                Roles:
                """);

        // Maintain original semantic ordering (brahma/coder first)
        // and include newer roles (architect).
        java.util.List<com.arcclaw.core.agent.AgentRoleEnum> roles = java.util.List.of(
                com.arcclaw.core.agent.AgentRoleEnum.CODER,
                com.arcclaw.core.agent.AgentRoleEnum.DEBUGGER,
                com.arcclaw.core.agent.AgentRoleEnum.RESEARCHER,
                com.arcclaw.core.agent.AgentRoleEnum.COORDINATOR,
                com.arcclaw.core.agent.AgentRoleEnum.ARCHITECT
        );

        for (com.arcclaw.core.agent.AgentRoleEnum role : roles) {
            sb.append("- ").append(role.toScopedName()).append(": ")
              .append(role.definition().intentDescription()).append("\n");
        }

        sb.append("""

                PRIORITY ROUTING RULES:
                1. SCHEDULING OVERRIDES ALL CONTEXT: If the user requests to schedule a task, set a reminder, or delay execution (e.g., "after 1 min", "schedule", "remind me", "every day"), you MUST route the request to 'coordinator'. Do this EVEN IF the request mentions GitHub, code, or research. The 'coordinator' is the only persona authorized to schedule future tasks.

                CRITICAL GUARDRAILS:
                1. (UNLESS SCHEDULING) If the user mentions GitHub, gists, or repositories to LIST, FETCH, or VIEW existing content -> researcher.
                2. (UNLESS SCHEDULING) If the user mentions GitHub, gists, or repositories to CREATE, BUILD, or GENERATE new content -> coder.
                3. Only output 'debugger' if the user provides actual error text, stack traces, or explicitly says "fix", "broken", "not working" with technical detail. Negative words like "no", "none", or "missing" alone are NOT debugging signals.
                4. "Hi", "Hello", greetings, or vague messages -> coordinator only.
                5. Phrases like "then in X", "and then X", "also X" are CONTINUATION
                   phrases joining two code/writing requests — NEVER treat them as
                   scheduling signals. Only route to coordinator for scheduling if the
                   user explicitly uses time words like "in 5 mins", "at 3pm",
                   "every day", "after 2 hours", "remind me", "schedule".

                User message: "%s"

                Reply with one word only:\s""");

        for (int i = 0; i < roles.size(); i++) {
            sb.append(roles.get(i).toScopedName());
            if (i < roles.size() - 1) {
                if (i == roles.size() - 2) sb.append(", or ");
                else sb.append(", ");
            }
        }

        this.dynamicClassifierPrompt = sb.toString();
    }

    public String classify(String userMessage) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                String raw = classifierModel.chat(
                        dynamicClassifierPrompt.formatted(userMessage));
                String sanitizedRole = sanitize(raw.trim().toLowerCase());
                return destructiveFilter.filter(sanitizedRole, userMessage);
            }, managedExecutor).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.warnf("Intent classifier failed for '%s': %s — defaulting to coordinator",
                    userMessage.length() > 40 ? userMessage.substring(0, 40) + "..." : userMessage,
                    e.getMessage());
            return "coordinator";
        }
    }


    private String sanitize(String raw) {
        String lowerRaw = raw.toLowerCase();
        // Translate persona to actual functional duty
        if (lowerRaw.contains("shiva") || lowerRaw.contains("debugger")) return "debugger";
        if (lowerRaw.contains("brahma") || lowerRaw.contains("coder")) return "coder";
        if (lowerRaw.contains("saraswati") || lowerRaw.contains("researcher")) return "researcher";
        if (lowerRaw.contains("architect") || lowerRaw.contains("brahaspati")) return "architect";
        return "coordinator";
    }
}