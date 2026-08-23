package com.amsha.tools.builtin.memory;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.memory.tiered.CoreMemoryService;
import com.amsha.memory.tiered.MemoryWriteGovernance;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolMetadata;
import com.amsha.tool.api.ToolResponse;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Agent tool for replacing a core memory block's content.
 * <p>
 * Overwrites the entire block content (unlike append which adds to existing).
 * Uses CAS versioning via {@link CoreMemoryService#upsert} to prevent conflicts.
 * <p>
 * Rate limited to {@code amsha.memory.governance.core-writes-per-minute} (default 5).
 * All mutations are audit-logged.
 */
@ApplicationScoped
@Unremovable
@ToolMetadata(
        capabilityId = "core_memory_replace",
        description = "Replace the entire content of a core memory block. "
                + "Rate limited to 5 writes/minute. CAS versioning prevents conflicts. "
                + "Keys: user_profile, active_task, constraints, project_context, preferences.",
        defaultRoles = {"architect", "coordinator", "researcher", "synthesizer", "coder"})
public class CoreMemoryReplaceTool implements AgentTool {

    private static final Logger log = Logger.getLogger(CoreMemoryReplaceTool.class);
    private static final String PARAM_BLOCK_KEY = "block_key";
    private static final String PARAM_CONTENT = "content";

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
        return "Replace the entire content of a core memory block. " + "Rate limited to 5 writes/minute.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty(
                        PARAM_BLOCK_KEY,
                        "Block key to replace. One of: user_profile, active_task, constraints, project_context, preferences.")
                .addStringProperty(PARAM_CONTENT, "New content for the block (replaces existing content entirely).")
                .required(PARAM_BLOCK_KEY, PARAM_CONTENT)
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
            String blockKey = (String) params.get(PARAM_BLOCK_KEY);
            String content = (String) params.get(PARAM_CONTENT);

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
                    ctx.sessionId(),
                    userId,
                    ctx.agentRole(),
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
