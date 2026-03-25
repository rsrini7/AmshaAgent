package com.arcclaw.core.reflex;

import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.CompactionSummaryRepository;
import com.arcclaw.core.state.StatefulAgentStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ResetReflex implements AgentReflex {

    @Inject
    Logger log;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Inject
    CompactionSummaryRepository compactionSummaryRepository;

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    StatefulAgentStore statefulAgentStore;

    private static final Pattern MATCHES_PATTERN = 
    Pattern.compile("(?i)^(/reset|clear\\s+(my\\s+)?history|reset\\s+(my\\s+)?history)$");

    @Override
    public boolean matches(String input) {
        return input != null && MATCHES_PATTERN.matcher(input.trim()).matches();
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        log.infof("[ResetReflex] User requested session reset for %s", sessionId);

        // 1. Soft workspace reset — clears memory, workspace dir, CDI scope
        //    Does NOT remove heartbeat. Does NOT clear governance budget.
        workspaceManager.resetSessionState(sessionId);

        // 2. Wipe compaction ghost memory
        compactionSummaryRepository.deleteBySessionId(sessionId);

        // 3. Mark stale checkpoints FAILED so handleResumeHook never fires
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            statefulAgentStore.load(sessionId, role.toScopedName()).ifPresent(cp -> {
                cp.setStatus(CheckpointStatus.FAILED);
                cp.setFailReason("Session reset by user.");
                statefulAgentStore.save(cp);
            });
        }

        // 4. Clear stale cron jobs — session-scoped only, NOT user-scoped
        long deletedCrons = cronRepository.deleteAllBySessionId(sessionId);

        log.infof("[ResetReflex] Session %s soft-reset — workspace, checkpoints, memory, " +
                "compaction, %d cron job(s) cleared. Heartbeat and budget retained.", 
                sessionId, deletedCrons);

        String output = "🧹 Conversation history, memory, checkpoints, and " + deletedCrons
                + " scheduled task(s) have been completely wiped. We are starting fresh.";
        return ReflexResult.respond(output, context);
    }
}
