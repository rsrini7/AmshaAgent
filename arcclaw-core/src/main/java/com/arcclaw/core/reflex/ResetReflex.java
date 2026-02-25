package com.arcclaw.core.reflex;

import com.arcclaw.core.lifecycle.WorkspaceManager;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ResetReflex implements AgentReflex {

    @Inject
    Logger log;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Override
    public boolean matches(String input) {
        return input != null && input.trim().equalsIgnoreCase("/reset");
    }

    @Override
    public String execute(String sessionId, String userId, String input) {
        log.infof("[ResetReflex] User requested session reset for %s", sessionId);
        
        // 1. Wipe the workspace scope (clears IN_PROGRESS / AWAITING_APPROVAL state)
        workspaceManager.terminateSession(sessionId);

        // 2. Wipe persistent chat memory from SQLite so context poisoning is cleared
        chatMemoryStore.deleteMessages(sessionId);

        log.infof("[ResetReflex] Session %s fully reset — workspace and chat memory cleared", sessionId);
        return "🧹 Session wiped. Memory and tool states cleared. Ready for a new task.";
    }
}
