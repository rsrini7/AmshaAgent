package com.arcclaw.core.reflex;

import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.state.CompactionSummaryRepository;

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

        // 3. NEW: Wipe the compaction ghost memory
        compactionSummaryRepository.deleteBySessionId(sessionId);

        log.infof("[ResetReflex] Session %s fully reset — workspace, chat memory, and compacted memory cleared", sessionId);
        return "🧹 Conversation history, memory, and tool states have been completely wiped. We are starting fresh.";
    }
}
