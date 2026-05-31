package com.arcclaw.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.util.ClassUtils;

/**
 * Lists all registered reflexes with their priority and trigger pattern.
 */
@ApplicationScoped
@ReflexPriority(500)
public class ListReflexesReflex implements AgentReflex {

    private record ReflexDescriptor(String trigger, String purpose) {}

    private static final Map<Class<?>, ReflexDescriptor> DESCRIPTORS = Map.ofEntries(
            Map.entry(ListCronsReflex.class, new ReflexDescriptor("`/crons`", "List scheduled jobs")),
            Map.entry(DeleteCronReflex.class, new ReflexDescriptor("`/delete-cron`", "Delete a scheduled job")),
            Map.entry(StatusReflex.class, new ReflexDescriptor("`/status`", "Show agent status")),
            Map.entry(HistoryReflex.class, new ReflexDescriptor("`/history`", "Show recent chat history")),
            Map.entry(ResetReflex.class, new ReflexDescriptor("`/reset` `/resethard`", "Reset session")),
            Map.entry(HaltReflex.class, new ReflexDescriptor("`/halt`", "Stop current task")),
            Map.entry(RetryReflex.class, new ReflexDescriptor("`/retry`", "Retry last action")),
            Map.entry(ApproveReflex.class, new ReflexDescriptor("`/approve`", "Approve pending action")),
            Map.entry(DenyReflex.class, new ReflexDescriptor("`/deny`", "Deny pending action")),
            Map.entry(StoreReflex.class, new ReflexDescriptor("`/store`", "Store key-value pair")),
            Map.entry(VaultStoreReflex.class, new ReflexDescriptor("`/vault`", "Store encrypted secret")),
            Map.entry(
                    ListReflexesReflex.class,
                    new ReflexDescriptor("`/reflex` `/commands` `/help`", "List all commands")),
            Map.entry(PromptInjectionReflex.class, new ReflexDescriptor("(auto)", "Block prompt injection")),
            Map.entry(PiiScanReflex.class, new ReflexDescriptor("(auto)", "Scan/redact PII")),
            Map.entry(IdentityExtractionReflex.class, new ReflexDescriptor("(auto)", "Extract user identity")));

    @Inject
    ReflexRegistry reflexRegistry;

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        String t = input.trim().toLowerCase();
        return t.equals("/reflex") || t.equals("/reflexes") || t.equals("/commands") || t.equals("/help");
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {

        List<AgentReflex> reflexes = reflexRegistry.getSortedReflexes();

        StringBuilder sb = new StringBuilder();
        sb.append("**Registered Reflexes (").append(reflexes.size()).append(")**\n\n");
        sb.append("| Command | Purpose |\n");
        sb.append("|---------|--------|\n");

        for (AgentReflex reflex : reflexes) {
            ReflexDescriptor desc = resolveDescriptor(reflex);
            sb.append(String.format("| %s | %s |\n", desc.trigger(), desc.purpose()));
        }

        return ReflexResult.respond(sb.toString(), context);
    }

    private ReflexDescriptor resolveDescriptor(AgentReflex reflex) {
        Class<?> reflexClass = reflex.getClass();
        // Unwrap CDI proxy: Quarkus @ApplicationScoped beans are proxied,
        // so reflex.getClass() returns e.g. ApproveReflex_ClientProxy
        String cleanName = ClassUtils.getCleanName(reflexClass);
        for (var entry : DESCRIPTORS.entrySet()) {
            if (ClassUtils.getCleanName(entry.getKey()).equals(cleanName)) {
                return entry.getValue();
            }
        }

        // Handle cross-module reflexes loaded from different classloaders
        String simpleName = reflex.getClass().getSimpleName();
        if (simpleName.equals("SkillReflex")) return new ReflexDescriptor("`/skills`", "List available skills");
        if (simpleName.equals("McpReflex")) return new ReflexDescriptor("`/mcp`", "Show MCP server status");
        return new ReflexDescriptor("\u2014", "\u2014");
    }
}
