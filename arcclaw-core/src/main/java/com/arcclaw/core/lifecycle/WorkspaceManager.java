package com.arcclaw.core.lifecycle;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.persistence.SqliteErrorStrings;
import com.arcclaw.core.scope.WorkspaceScopeContext;
import com.arcclaw.core.scope.WorkspaceScoped;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Lifecycle orchestrator for workspace session termination and disk management.
 * <p>
 * <strong>Responsibilities:</strong>
 * <ul>
 * <li><b>Termination:</b> Mark checkpoints FAILED, wipe workspace dir, destroy CDI scope.</li>
 * <li><b>Disk Management:</b> Physical cleanup of workspace directories.</li>
 * </ul>
 */
@ApplicationScoped
public class WorkspaceManager {

    private static final Logger log = Logger.getLogger(WorkspaceManager.class);

    @Inject
    EntityManager em;

    @Inject
    WorkspaceManager self;

    @Inject
    GovernanceLayer governanceLayer;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @ConfigProperty(name = "arcclaw.chat-memory.max-messages", defaultValue = "100")
    int maxMessages;

    // -------------------------------------------------------------------------
    // Session Termination
    // -------------------------------------------------------------------------

    /**
     * Terminate a workspace session completely.
     */
    public void terminateSession(String sessionId) {
        log.infof("Terminating session: %s", sessionId);

        // 1. Remove from heartbeat tracking
        heartbeatTracker.removeHeartbeat(sessionId);

        // 1a. Clear budget memory
        governanceLayer.clearSession(sessionId);

        // 2. Mark checkpoints FAILED (in a transaction)
        markCheckpointsFailed(sessionId);

        // 3. Wipe workspace directory
        wipeWorkspaceDir(sessionId);

        // 4. Clear in-memory LangChain4j ChatMemory
        clearInMemoryChatMemory(sessionId);

        // 5. Delete chat memory from persistence
        try {
            chatMemoryStore.deleteMessages(sessionId);
            log.debugf("Chat memory deleted for session=%s", sessionId);
        } catch (Exception e) {
            log.warnf("Failed to delete chat memory for session=%s: %s", sessionId, e.getMessage());
        }

        // 6. Destroy CDI scope
        try {
            var context = (WorkspaceScopeContext) Arc.container()
                    .getActiveContext(WorkspaceScoped.class);
            if (context != null) {
                context.destroySession(sessionId);
                log.infof("CDI scope destroyed for session=%s", sessionId);
            }
        } catch (Exception e) {
            log.warnf("Failed to destroy CDI scope for session=%s: %s", sessionId, e.getMessage());
        }
    }

    /**
     * Reset the session state while keeping the session record (WORM compliance).
     */
    public void resetSessionState(String sessionId) {
        log.infof("Resetting session state for: %s", sessionId);
        
        clearInMemoryChatMemory(sessionId);
        
        try {
            chatMemoryStore.deleteMessages(sessionId);
            log.debugf("Chat memory deleted for session=%s", sessionId);
        } catch (Exception e) {
            log.warnf("Failed to delete chat memory for session=%s: %s", sessionId, e.getMessage());
        }
    }

    private void clearInMemoryChatMemory(String sessionId) {
        try {
            MessageWindowChatMemory.builder()
                    .id(sessionId)
                    .maxMessages(maxMessages)
                    .chatMemoryStore(chatMemoryStore)
                    .build()
                    .clear();
            log.debugf("In-memory chat memory cleared for session=%s", sessionId);
        } catch (Exception e) {
            log.warnf("Failed to clear in-memory chat memory for session=%s (non-fatal): %s", 
                sessionId, e.getMessage());
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void doMarkCheckpointsFailedRequiresNew(String sessionId) {
        em.clear();
        List<AgentCheckpoint> checkpoints = em.createQuery(
                "SELECT c FROM AgentCheckpoint c WHERE c.sessionId = :sid AND c.status != :failed",
                AgentCheckpoint.class)
                .setParameter("sid", sessionId)
                .setParameter("failed", CheckpointStatus.FAILED)
                .getResultList();

        Instant now = Instant.now();
        for (var checkpoint : checkpoints) {
            checkpoint.setStatus(CheckpointStatus.FAILED);
            checkpoint.setFailedAt(now);
            checkpoint.setFailReason("Session terminated by WorkspaceManager");
        }
        if (!checkpoints.isEmpty()) {
            log.infof("Marked %d checkpoint(s) FAILED for session=%s", checkpoints.size(), sessionId);
        }
    }

    void markCheckpointsFailed(String sessionId) {
        int maxRetries = 5;
        long backoffMs = 50;
        Exception lastEx = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                self.doMarkCheckpointsFailedRequiresNew(sessionId);
                return; // success
            } catch (Exception e) {
                lastEx = e;
                if (isSqliteBusy(e)) {
                    log.warnf("SQLite BUSY on markCheckpointsFailed attempt %d/%d for session=%s — retrying in %dms",
                            attempt, maxRetries, sessionId, backoffMs * attempt);
                    try { Thread.sleep(backoffMs * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                } else {
                    log.errorf(e, "Non-retryable error marking checkpoints FAILED for session=%s", sessionId);
                    throw e;
                }
            }
        }
        log.errorf(lastEx, "Failed to mark checkpoints FAILED after %d retries for session=%s", maxRetries, sessionId);
        if (lastEx != null) throw new RuntimeException("Failed to mark checkpoints FAILED for session " + sessionId, lastEx);
    }

    private static boolean isSqliteBusy(Throwable e) {
        // Intentional copy — also exists in SessionReaper.
        // Kept private static to avoid extracting a shared utility for one method.
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains(SqliteErrorStrings.BUSY) || 
                    lower.contains(SqliteErrorStrings.DATABASE_LOCKED) || 
                    lower.contains(SqliteErrorStrings.SQLITE_BUSY) ||
                    lower.contains(SqliteErrorStrings.SNAPSHOT)) {
                    return true;
                }
            }
            if (t.getClass().getSimpleName().equals(SqliteErrorStrings.SQLITE_EXCEPTION)) {
                try {
                    var method = t.getClass().getMethod("getResultCode");
                    Object code = method.invoke(t);
                    if (code instanceof Integer rc) {
                        if (rc == 5 || rc == 6 || rc == 261) return true;
                    }
                } catch (Exception ignored) { }
            }
        }
        return false;
    }

    private void wipeWorkspaceDir(String sessionId) {
        Path workspaceDir = Path.of(workspaceRootDir, sessionId);
        if (!Files.exists(workspaceDir)) {
            return;
        }
        try {
            Files.walkFileTree(workspaceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.infof("Workspace directory wiped: %s", workspaceDir);
        } catch (IOException e) {
            log.warnf("Failed to wipe workspace dir %s: %s", workspaceDir, e.getMessage());
        }
    }
}
