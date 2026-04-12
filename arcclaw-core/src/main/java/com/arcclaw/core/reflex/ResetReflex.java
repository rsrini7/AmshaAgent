package com.arcclaw.core.reflex;

import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.message.UserMessages;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.AgentCronRepository;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.CompactionSummaryRepository;
import com.arcclaw.core.state.StatefulAgentStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.jboss.logging.Logger;

@ApplicationScoped
@ReflexPriority(500)
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
    Pattern.compile("(?i)^(/reset|/resethard|clear\\s+all|clear\\s+(my\\s+)?history|reset\\s+(my\\s+)?history)$");

    @Override
    public boolean matches(String input) {
        return input != null && MATCHES_PATTERN.matcher(input.trim()).matches();
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        log.infof("[ResetReflex] User requested session reset for %s", sessionId);

        String trimmed = input.trim().toLowerCase();
        boolean isHardReset = trimmed.contains("hard") || trimmed.contains("clear all");

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

        // 4. Wipe chat history ONLY if hard reset was requested
        if (isHardReset) {
            chatMemoryStore.deleteMessages(sessionId);
        }

        // 5. deletes checkpoint (StatusReflex shows NOT_STARTED = NO_ACTIVE_STATE)
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            statefulAgentStore.deleteCheckpoint(sessionId, role.toScopedName());
        }

        // 6. Clear stale cron jobs — session-scoped only, NOT user-scoped
        long deletedCrons = cronRepository.deleteAllBySessionId(sessionId);

        String logStatus = isHardReset ? "chat history cleared" : "Chat memory retained";
        log.infof("[ResetReflex] Session %s reset — workspace, checkpoints, " +
        "compaction, %d cron job(s) cleared. %s. Heartbeat and budget retained.",
        sessionId, deletedCrons, logStatus);

        String replyMessage = isHardReset 
            ? "💥 *Hard Reset Complete.* Agent state, checkpoints, and chat history have been wiped. You are starting with a clean slate."
            : UserMessages.sessionReset(deletedCrons);

        return ReflexResult.respond(replyMessage, context);
    }
}
