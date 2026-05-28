package com.arcclaw.core.tools.memory;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.memory.tiered.CoreMemoryService;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.IdempotencyMode;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.persistence.api.CoreMemoryBlockDto;

import org.jboss.logging.Logger;

/**
 * Agent tool for reading core memory blocks.
 * <p>
 * Returns all blocks for the current user, or a specific block by key.
 * Read-only — no approval or rate limiting required.
 */
@ApplicationScoped
@ToolMetadata(
        capabilityId = "core_memory_read",
        description =
                "Read core memory blocks for the current user. " + "Returns all blocks or a specific block by key. "
                        + "Block keys: user_profile, active_task, constraints, project_context, preferences.",
        defaultRoles = {"architect", "coordinator", "researcher", "synthesizer", "coder"})
public class CoreMemoryReadTool implements AgentTool {

    private static final Logger log = Logger.getLogger(CoreMemoryReadTool.class);

    @Inject
    CoreMemoryService coreMemoryService;

    @Override
    public String name() {
        return ToolCapability.CORE_MEMORY_READ.toolName();
    }

    @Override
    public String description() {
        return "Read core memory blocks (user profile, active task, constraints, project context, preferences). "
                + "Returns all blocks or a specific block by key.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("block_key", "Optional specific block key to read. Omit to read all blocks.")
                .build();
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.READ_FS;
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

            if (blockKey != null && !blockKey.isBlank()) {
                Optional<CoreMemoryBlockDto> block = coreMemoryService.get(userId, blockKey.trim());
                if (block.isPresent()) {
                    CoreMemoryBlockDto b = block.get();
                    return ToolResponse.success(formatBlock(b));
                }
                return ToolResponse.success("No block found for key: " + blockKey);
            }

            List<CoreMemoryBlockDto> blocks = coreMemoryService.readAll(userId);
            if (blocks.isEmpty()) {
                return ToolResponse.success("No core memory blocks found for this user.");
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < blocks.size(); i++) {
                if (i > 0) sb.append("\n\n");
                sb.append(formatBlock(blocks.get(i)));
            }
            return ToolResponse.success(sb.toString());

        } catch (Exception e) {
            log.errorf("Core memory read failed: %s", e.getMessage());
            return ToolResponse.failure(1, "Core memory read error: " + e.getMessage());
        }
    }

    private String formatBlock(CoreMemoryBlockDto block) {
        return String.format("### [%s] (v%d)\n%s", block.blockKey(), block.version(), block.content());
    }
}
