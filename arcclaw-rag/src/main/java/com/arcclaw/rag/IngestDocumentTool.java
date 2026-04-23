package com.arcclaw.rag;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.IdempotencyMode;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.persistence.api.IngestResult;
import com.arcclaw.persistence.api.MemoryEntryDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

@ApplicationScoped
public class IngestDocumentTool implements AgentTool {

    @Inject MemoryRetrievalPort memoryPort;
    @Inject AuditService auditService;

    @Override
    public String name() {
        return ToolCapability.INGEST_DOCUMENT.toolName();
    }

    @Override
    public String description() {
        return "Ingest a text document into the RAG knowledge base for future retrieval. " +
               "Use this when the user provides a document, code, or reference material " +
               "that should be stored and searchable in future queries.";
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.READ_FS;
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public IdempotencyMode idempotencyMode() {
        return IdempotencyMode.AT_MOST_ONCE;
    }


    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("text",
                "The full factual statement or document text to store, e.g. 'The user's name is Srini'. " +
                "Do NOT store just an entity name — store the complete fact as a standalone sentence.")
            .addStringProperty("label",
                "A descriptive short label or key for this information (e.g. 'user_name', 'project_overview').")
            .required("text")
            .build();
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        String userId = WorkspaceContext.requireUserId();
        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403,
                "Security violation: tenant context not bound.");
        }

        String text = (String) params.get("text");
        if (text == null || text.isBlank()) {
            return ToolResponse.failure(400, "text parameter is required.");
        }

        text = sanitize(text);  // ← sanitize before blank re-check

        if (text.isBlank()) {
            return ToolResponse.failure(400,
                "text is empty after sanitization — nothing to ingest.");
        }

        String label = params.containsKey("label")
            ? (String) params.get("label") : "unlabeled";

        String sessionId = WorkspaceScopeHolder.getIfActive();
        if (sessionId == null) sessionId = "no-session";

        IngestResult result = memoryPort.index(new MemoryEntryDto(null, text, Map.of("label", label)));

        if (result.isGlobalFallback()) {
            return ToolResponse.success(
                "Document ingested into SESSION memory only (Global KB is currently offline). chunkId: " + result.id());
        }

        return ToolResponse.success(
            "Document ingested successfully into Global KB. chunkId: " + result.id());
    }

    @Override
    public boolean requiresManualApproval(Map<String, Object> params) {
        // Gate ingest like github/sandbox tools
        return true; // always require approval
    }

    /**
     * Sanitizes raw text before ingestion.
     * Groq's function-call validator rejects payloads containing
     * consecutive dashes, raw markdown, or control characters.
     * This also produces cleaner embedding vectors — stripped text
     * embeds on semantic content, not formatting noise.
     */
    private String sanitize(String raw) {
        return raw
            .replaceAll("-{2,}", "-")
            .replaceAll("={2,}", "")
            .replaceAll("\\*{2,}", "")
            .replaceAll("(?m)^```.*$", "")
            .replaceAll("(?m)^#{1,6}\\s+", "")
            .replaceAll("(?m)(\\n\\s*){3,}", "\n\n")
            .replaceAll("[\\p{Cntrl}&&[^\n\t]]", "") // control chars except \n and \t
            .strip();
    }
}
