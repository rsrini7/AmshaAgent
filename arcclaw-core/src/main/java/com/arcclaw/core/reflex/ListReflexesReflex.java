package com.arcclaw.core.reflex;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.util.ClassUtils;

/**
 * Lists all registered reflexes with their priority and trigger pattern.
 */
@ApplicationScoped
@ReflexPriority(500)
public class ListReflexesReflex implements AgentReflex {

    @Inject
    ReflexRegistry reflexRegistry;

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        String t = input.trim().toLowerCase();
        return t.equals("/reflex") || t.equals("/reflexes") || t.equals("/commands") || t.equals("/help");
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId,
                                String input, MessageContext context) {

        List<AgentReflex> reflexes = reflexRegistry.getSortedReflexes();

        StringBuilder sb = new StringBuilder();
        sb.append("**Registered Reflexes (").append(reflexes.size()).append(")**\n\n");
        sb.append("| # | Reflex | Priority | Trigger | Type |\n");
        sb.append("|---|--------|----------|---------|------|\n");

        int i = 1;
        for (AgentReflex reflex : reflexes) {
            String name = ClassUtils.getCleanName(reflex);
            int priority = reflex.priority();
            String trigger = resolveTrigger(reflex);
            String type = resolveType(reflex);

            sb.append(String.format("| %d | `%s` | %d | %s | %s |\n",
                    i++, name, priority, trigger, type));
        }

        sb.append("\n_Type: **Cmd** = slash command, **Auto** = automatic detection, **Sanitize** = input transformation_");

        return ReflexResult.respond(sb.toString(), context);
    }

    private String resolveTrigger(AgentReflex reflex) {
        if (reflex instanceof ListCronsReflex) return "`/crons`";
        if (reflex instanceof DeleteCronReflex) return "`/delete-cron`";
        if (reflex instanceof StatusReflex) return "`/status`";
        if (reflex instanceof HistoryReflex) return "`/history`";
        if (reflex instanceof ResetReflex) return "`/reset` `/resethard` `clear all`";
        if (reflex instanceof HaltReflex) return "`/halt`";
        if (reflex instanceof RetryReflex) return "`/retry`";
        if (reflex instanceof ApproveReflex) return "`/approve`";
        if (reflex instanceof DenyReflex) return "`/deny`";
        if (reflex instanceof StoreReflex) return "`/store`";
        if (reflex instanceof VaultStoreReflex) return "`/vault` `/vault-store`";
        if (reflex instanceof ListReflexesReflex) return "`/reflex` `/reflexes` `/commands` `/help`";
        if (reflex instanceof PromptInjectionReflex) return "injection patterns";
        if (reflex instanceof PiiScanReflex) return "any message";
        if (reflex instanceof IdentityExtractionReflex) return "identity patterns";
        // Cross-module reflexes (skill, mcp) — check by class name to avoid circular imports
        String simpleName = reflex.getClass().getSimpleName();
        if (simpleName.equals("SkillReflex")) return "`/skills`";
        if (simpleName.equals("McpReflex")) return "`/mcp`";
        return "—";
    }

    private String resolveType(AgentReflex reflex) {
        if (reflex instanceof PromptInjectionReflex) return "Auto";
        if (reflex instanceof PiiScanReflex) return "Sanitize";
        if (reflex instanceof IdentityExtractionReflex) return "Auto";
        return "Cmd";
    }
}
