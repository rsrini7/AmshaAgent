package com.arcclaw.core.lifecycle;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.arcclaw.core.audit.AuditObserver;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.persistence.SqliteRetryTemplate;
import com.arcclaw.core.scope.WorkspaceScopeContext;
import com.arcclaw.core.scope.WorkspaceScoped;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.arc.Arc;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
    GovernanceStrategy governanceLayer;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    SqliteRetryTemplate retryTemplate;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @ConfigProperty(name = "arcclaw.chat-memory.max-messages", defaultValue = "100")
    int maxMessages;

    
    @Inject
    AuditObserver auditObserver;

    // Session Termination
    
    /**
     * Terminate a workspace session completely.
     */
    public void terminateSession(String sessionId) {
        log.infof("Terminating session: %s", sessionId);

        // 1. Remove from heartbeat tracking
        heartbeatTracker.removeHeartbeat(sessionId);

        // 1a. Clear budget memory
        governanceLayer.clearSession(sessionId);

        // 1b. Cleanup audit observer
        if (sessionId != null) {
            auditObserver.cleanupSession(sessionId);
        }

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

        // §7.29: Clear any in-flight batch resume flag for this session.
        statefulAgentStore.clearBatchResuming(sessionId);
    }

    /**
     * Reset the session state while keeping the session record (WORM compliance).
     */
    public void resetSessionState(String sessionId) {
        log.infof("Soft-resetting session state for: %s", sessionId);

        // Chat memory intentionally NOT cleared here.
        // Soft reset preserves the conversation window so the user
        // can continue the same dialogue after /reset.
        // Only checkpoint state, workspace artifacts, and CDI scope are wiped.

        // Stale workspace artifacts
        wipeWorkspaceDir(sessionId);

        // CDI scope — destroy so @WorkspaceScoped beans re-init on next bind
        try {
            var context = (WorkspaceScopeContext) Arc.container()
                    .getActiveContext(WorkspaceScoped.class);
            if (context != null) {
                context.destroySession(sessionId);
            }
        } catch (Exception e) {
            log.warnf("CDI scope reset failed for session=%s: %s", sessionId, e.getMessage());
        }

        // heartbeatTracker — NOT touched ✅
        // governanceLayer   — NOT touched ✅ (budget survives per REFLEX-5)
        // chatMemoryStore   — NOT touched ✅ (conversation window survives soft reset)
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
        try {
            retryTemplate.executeVoid("markFailed-" + sessionId, 5, 50, () -> {
                self.doMarkCheckpointsFailedRequiresNew(sessionId);
            });
        } catch (Exception e) {
            log.errorf("Failed to mark checkpoints FAILED after retries for session=%s: %s", sessionId, e.getMessage());
            log.debugf(e, "Full stacktrace for retry exhaustion in markCheckpointsFailed for session=%s", sessionId);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException("Failed to mark checkpoints FAILED for session " + sessionId, e);
        }
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
