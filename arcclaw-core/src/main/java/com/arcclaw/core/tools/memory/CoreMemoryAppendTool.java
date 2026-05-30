package com.arcclaw.core.tools.memory;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

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

import org.jboss.logging.Logger;

/**
 * Agent tool for appending to a core memory block.
 *
 * <p>
 * If the block exists, appends the new content separated by a newline.
 * If the block does not exist, creates it.
 * Uses atomic append via {@link CoreMemoryService#append} — read-modify-write
 * is performed inside the CAS retry loop to prevent lost updates under concurrency.
 * <p>
 * Rate limited to {@code arcclaw.memory.governance.core-writes-per-minute} (default 5).
 * All mutations are audit-logged.
 */
@ApplicationScoped
@ToolMetadata(
        capabilityId = "core_memory_append",
        description = "Append content to a core memory block (or create if missing). "
                + "Rate limited to 5 writes/minute. CAS versioning prevents conflicts. "
                + "Keys: user_profile, active_task, constraints, project_context, preferences.",
        defaultRoles = {"architect", "coordinator", "researcher", "synthesizer", "coder"})
public class CoreMemoryAppendTool implements AgentTool {

    private static final Logger log = Logger.getLogger(CoreMemoryAppendTool.class);
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
        return ToolCapability.CORE_MEMORY_APPEND.toolName();
    }

    @Override
    public String description() {
        return "Append content to a core memory block (or create if missing). " + "Rate limited to 5 writes/minute.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty(
                        PARAM_BLOCK_KEY,
                        "Block key to append to. One of: user_profile, active_task, constraints, project_context, preferences.")
                .addStringProperty(PARAM_CONTENT, "Content to append to the block.")
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

            // Atomic append: read-modify-write inside CAS retry loop
            coreMemoryService.append(userId, blockKey.trim(), content.trim());

            // Audit
            auditService.record(
                    ctx.sessionId(),
                    userId,
                    ctx.agentRole(),
                    SecurityEventType.MEMORY_WRITE_MUTATED,
                    "tool=core_memory_append block=" + blockKey);

            return ToolResponse.success("Appended to block '" + blockKey + "'.");

        } catch (MemoryWriteGovernance.RateLimitExceededException e) {
            return ToolResponse.failure(429, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResponse.failure(400, e.getMessage());
        } catch (Exception e) {
            log.errorf("Core memory append failed: %s", e.getMessage());
            return ToolResponse.failure(1, "Core memory append error: " + e.getMessage());
        }
    }
}
