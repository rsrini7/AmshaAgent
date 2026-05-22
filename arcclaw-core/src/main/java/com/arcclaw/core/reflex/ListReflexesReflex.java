package com.arcclaw.core.reflex;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.MessageContext;

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
        sb.append("| Command | Purpose |\n");
        sb.append("|---------|--------|\n");

        for (AgentReflex reflex : reflexes) {
            String trigger = resolveTrigger(reflex);
            String purpose = resolvePurpose(reflex);

            sb.append(String.format("| %s | %s |\n", trigger, purpose));
        }

        return ReflexResult.respond(sb.toString(), context);
    }

    private String resolveTrigger(AgentReflex reflex) {
        if (reflex instanceof ListCronsReflex) return "`/crons`";
        if (reflex instanceof DeleteCronReflex) return "`/delete-cron`";
        if (reflex instanceof StatusReflex) return "`/status`";
        if (reflex instanceof HistoryReflex) return "`/history`";
        if (reflex instanceof ResetReflex) return "`/reset` `/resethard`";
        if (reflex instanceof HaltReflex) return "`/halt`";
        if (reflex instanceof RetryReflex) return "`/retry`";
        if (reflex instanceof ApproveReflex) return "`/approve`";
        if (reflex instanceof DenyReflex) return "`/deny`";
        if (reflex instanceof StoreReflex) return "`/store`";
        if (reflex instanceof VaultStoreReflex) return "`/vault`";
        if (reflex instanceof ListReflexesReflex) return "`/reflex` `/commands` `/help`";
        if (reflex instanceof PromptInjectionReflex) return "(auto)";
        if (reflex instanceof PiiScanReflex) return "(auto)";
        if (reflex instanceof IdentityExtractionReflex) return "(auto)";
        String simpleName = reflex.getClass().getSimpleName();
        if (simpleName.equals("SkillReflex")) return "`/skills`";
        if (simpleName.equals("McpReflex")) return "`/mcp`";
        return "—";
    }

    private String resolvePurpose(AgentReflex reflex) {
        if (reflex instanceof ListCronsReflex) return "List scheduled jobs";
        if (reflex instanceof DeleteCronReflex) return "Delete a scheduled job";
        if (reflex instanceof StatusReflex) return "Show agent status";
        if (reflex instanceof HistoryReflex) return "Show recent chat history";
        if (reflex instanceof ResetReflex) return "Reset session";
        if (reflex instanceof HaltReflex) return "Stop current task";
        if (reflex instanceof RetryReflex) return "Retry last action";
        if (reflex instanceof ApproveReflex) return "Approve pending action";
        if (reflex instanceof DenyReflex) return "Deny pending action";
        if (reflex instanceof StoreReflex) return "Store key-value pair";
        if (reflex instanceof VaultStoreReflex) return "Store encrypted secret";
        if (reflex instanceof ListReflexesReflex) return "List all commands";
        if (reflex instanceof PromptInjectionReflex) return "Block prompt injection";
        if (reflex instanceof PiiScanReflex) return "Scan/redact PII";
        if (reflex instanceof IdentityExtractionReflex) return "Extract user identity";
        String simpleName = reflex.getClass().getSimpleName();
        if (simpleName.equals("SkillReflex")) return "List available skills";
        if (simpleName.equals("McpReflex")) return "Show MCP server status";
        return "—";
    }
}
