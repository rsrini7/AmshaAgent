package com.arcclaw.core.reflex;

import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.state.CompactionSummaryRepository;
import com.arcclaw.core.state.AgentCronRepository;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.regex.Pattern;

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

    private static final Pattern MATCHES_PATTERN = 
    Pattern.compile("(?i)^(/reset|clear\\s+(my\\s+)?history|reset\\s+(my\\s+)?history)$");

    @Override
    public boolean matches(String input) {
        return input != null && MATCHES_PATTERN.matcher(input.trim()).matches();
    }

    @Override
    public String execute(String sessionId, String userId, String input) {
        log.infof("[ResetReflex] User requested session reset for %s", sessionId);
        
        // 1. Wipe the workspace scope (clears IN_PROGRESS / AWAITING_APPROVAL state)
        workspaceManager.terminateSession(sessionId);

        // 2. Wipe persistent chat memory from SQLite so context poisoning is cleared
        chatMemoryStore.deleteMessages(sessionId);

        // 3. Wipe the compaction ghost memory
        compactionSummaryRepository.deleteBySessionId(sessionId);

        // 4. NEW: Wipe all scheduled background cron jobs for this WORM tenant
        long deletedCrons = cronRepository.deleteAllByUserId(userId);

        log.infof("[ResetReflex] Session %s fully reset — workspace, chat memory, compacted memory, and %d cron jobs cleared", 
                sessionId, deletedCrons);
                
        return "🧹 Conversation history, memory, and " + deletedCrons + " scheduled task(s) have been completely wiped. We are starting fresh.";
    }
}