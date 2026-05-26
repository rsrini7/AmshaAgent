package com.arcclaw.core.tools.memory;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.memory.tiered.CoreMemoryService;
import com.arcclaw.core.memory.tiered.MemoryWriteGovernance;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.IdempotencyMode;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.jboss.logging.Logger;

/**
 * Agent tool for replacing a core memory block's content.
 * <p>
 * Overwrites the entire block content (unlike append which adds to existing).
 * Uses CAS versioning via {@link CoreMemoryService#upsert} to prevent conflicts.
 * <p>
 * Rate limited to {@code arcclaw.memory.governance.core-writes-per-minute} (default 5).
 * All mutations are audit-logged.
 */
@ApplicationScoped
@ToolMetadata(
    capabilityId = "core_memory_replace",
    description = "Replace the entire content of a core memory block. " +
                  "Rate limited to 5 writes/minute. CAS versioning prevents conflicts. " +
                  "Keys: user_profile, active_task, constraints, project_context, preferences.",
    defaultRoles = {"architect", "coordinator", "researcher", "synthesizer", "coder"}
)
public class CoreMemoryReplaceTool implements AgentTool {

    private static final Logger log = Logger.getLogger(CoreMemoryReplaceTool.class);

    @Inject
    CoreMemoryService coreMemoryService;

    @Inject
    MemoryWriteGovernance governance;

    @Inject
    AuditService auditService;

    @Override
    public String name() {
        return ToolCapability.CORE_MEMORY_REPLACE.toolName();
    }

    @Override
    public String description() {
        return "Replace the entire content of a core memory block. " +
               "Rate limited to 5 writes/minute.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("block_key",
                "Block key to replace. One of: user_profile, active_task, constraints, project_context, preferences.")
            .addStringProperty("content",
                "New content for the block (replaces existing content entirely).")
            .required("block_key", "content")
            .build();
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.WRITE_MEMORY;
    }

    @Override
    public IdempotencyMode idempotencyMode() {
        return IdempotencyMode.IDEMPOTENT;
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        try {
            String userId = WorkspaceContext.getUserIdOrUnknown();
            String blockKey = (String) params.get("block_key");
            String content = (String) params.get("content");

            if (blockKey == null || blockKey.isBlank()) {
                return ToolResponse.failure(400, "'block_key' is required.");
            }
            if (content == null || content.isBlank()) {
                return ToolResponse.failure(400, "'content' is required.");
            }

            // Rate limit
            governance.checkCoreWrite();

            // Upsert with CAS — replaces entire block content
            coreMemoryService.upsert(userId, blockKey.trim(), content.trim());

            // Audit
            auditService.record(
                ctx.sessionId(), userId, ctx.agentRole(),
                SecurityEventType.MEMORY_WRITE_MUTATED,
                "tool=core_memory_replace block=" + blockKey);

            return ToolResponse.success("Replaced block '" + blockKey + "'.");

        } catch (MemoryWriteGovernance.RateLimitExceededException e) {
            return ToolResponse.failure(429, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResponse.failure(400, e.getMessage());
        } catch (Exception e) {
            log.errorf("Core memory replace failed: %s", e.getMessage());
            return ToolResponse.failure(1, "Core memory replace error: " + e.getMessage());
        }
    }
}
