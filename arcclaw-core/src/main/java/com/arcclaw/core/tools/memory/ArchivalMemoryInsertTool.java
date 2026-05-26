package com.arcclaw.core.tools.memory;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.config.TieredMemoryConfig;
import com.arcclaw.core.memory.tiered.ArchivalMemoryService;
import com.arcclaw.core.memory.tiered.MemoryWriteGovernance;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.IdempotencyMode;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.persistence.api.ArchivalChunkDto;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.jboss.logging.Logger;

/**
 * Agent tool for inserting knowledge chunks into long-term archival memory.
 * <p>
 * Approval-gated: requires manual approval for non-coordinator roles.
 * Rate limited to {@code arcclaw.memory.governance.archival-writes-per-hour} (default 10).
 * Content capped at 4000 characters.
 * All mutations are audit-logged.
 */
@ApplicationScoped
@ToolMetadata(
    capabilityId = "archival_memory_insert",
    description = "Insert a knowledge chunk into long-term archival memory. " +
                  "Requires approval. Rate limited to 10 inserts/hour. Max 4000 chars. " +
                  "Chunk types: CODE, DOC, PATTERN, FACT, PREFERENCE, DIRECTIVE.",
    defaultRoles = {"architect", "coordinator", "researcher", "synthesizer", "coder"}
)
public class ArchivalMemoryInsertTool implements AgentTool {

    private static final Logger log = Logger.getLogger(ArchivalMemoryInsertTool.class);

    private static final int MAX_CONTENT_CHARS = 4000;
    private static final Set<String> ALLOWED_CHUNK_TYPES = Set.of(
        "CODE", "DOC", "PATTERN", "FACT", "PREFERENCE", "DIRECTIVE"
    );

    @Inject
    ArchivalMemoryService archivalMemoryService;

    @Inject
    MemoryWriteGovernance governance;

    @Inject
    TieredMemoryConfig tieredConfig;

    @Inject
    AuditService auditService;

    @Override
    public String name() {
        return ToolCapability.ARCHIVAL_MEMORY_INSERT.toolName();
    }

    @Override
    public String description() {
        return "Insert a knowledge chunk into long-term archival memory. " +
               "Requires approval. Rate limited to 10 inserts/hour. Max 4000 chars.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("content",
                "The knowledge content to store (max 4000 characters).")
            .addStringProperty("chunk_type",
                "Type of knowledge: CODE, DOC, PATTERN, FACT, PREFERENCE, or DIRECTIVE.")
            .addStringProperty("tags",
                "Optional comma-separated tags for categorization.")
            .required("content", "chunk_type")
            .build();
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.WRITE_FS;
    }

    @Override
    public IdempotencyMode idempotencyMode() {
        return IdempotencyMode.NON_IDEMPOTENT;
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public boolean requiresManualApproval(Map<String, Object> params) {
        return true;
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        try {
            String userId = WorkspaceContext.getUserIdOrUnknown();
            String content = (String) params.get("content");
            String chunkType = (String) params.get("chunk_type");
            String tags = (String) params.get("tags");

            // Validate
            if (content == null || content.isBlank()) {
                return ToolResponse.failure(400, "'content' is required.");
            }
            if (chunkType == null || !ALLOWED_CHUNK_TYPES.contains(chunkType.toUpperCase())) {
                return ToolResponse.failure(400,
                    "'chunk_type' must be one of: " + ALLOWED_CHUNK_TYPES);
            }
            if (content.length() > MAX_CONTENT_CHARS) {
                return ToolResponse.failure(400,
                    "Content exceeds max length of " + MAX_CONTENT_CHARS + " characters.");
            }

            // Rate limit
            governance.checkArchivalWrite();

            // Build and insert
            ArchivalChunkDto chunk = new ArchivalChunkDto(
                UUID.randomUUID().toString(),
                userId,
                content.trim(),
                chunkType.toUpperCase(),
                ctx.sessionId(),
                null,  // sourceMessage
                tags,
                1.0,   // explicit insert = verified ground truth
                0,     // initial access count
                Instant.now(),
                Instant.now()
            );

            archivalMemoryService.upsert(chunk);

            // Audit
            auditService.record(
                ctx.sessionId(), userId, ctx.agentRole(),
                SecurityEventType.MEMORY_WRITE_MUTATED,
                "tool=archival_memory_insert chunk_type=" + chunkType + " id=" + chunk.chunkId());

            return ToolResponse.success("Inserted archival chunk: " + chunk.chunkId()
                + " (type=" + chunkType + ")");

        } catch (MemoryWriteGovernance.RateLimitExceededException e) {
            return ToolResponse.failure(429, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResponse.failure(400, e.getMessage());
        } catch (Exception e) {
            log.errorf("Archival memory insert failed: %s", e.getMessage());
            return ToolResponse.failure(1, "Archival memory insert error: " + e.getMessage());
        }
    }
}
