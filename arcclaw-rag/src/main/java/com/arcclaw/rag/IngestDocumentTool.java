package com.arcclaw.rag;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolResponse;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

@ApplicationScoped
public class IngestDocumentTool implements AgentTool {

    @Inject GlobalKnowledgeBase globalKb;
    @Inject SessionKnowledgeBase sessionKb;
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
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("text",
                "The document text to ingest into the knowledge base.")
            .addStringProperty("label",
                "Optional short label for the document (for audit).")
            .required("text")
            .build();
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        String userId = WorkspaceContext.userId();
        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403,
                "Security violation: tenant context not bound.");
        }

        String text = (String) params.get("text");
        if (text == null || text.isBlank()) {
            return ToolResponse.failure(400, "text parameter is required.");
        }

        String label = params.containsKey("label")
            ? (String) params.get("label") : "unlabeled";

        String sessionId = WorkspaceScopeHolder.getIfActive();
        if (sessionId == null) sessionId = "no-session";

        // Ingest into both tiers
        String chunkId = globalKb.ingest(text);
        sessionKb.ingest(chunkId != null ? chunkId : "session-" + label, text);

        auditService.record(sessionId, userId, "tool",
            SecurityEventType.TOOL_INVOKED,
            "tool=ingest_document label=" + label +
            " chars=" + text.length() +
            (chunkId != null ? " chunkId=" + chunkId : " vec=degraded"));

        return ToolResponse.success(
            chunkId != null
                ? "Document ingested successfully. chunkId: " + chunkId
                : "Document stored in session memory (persistent vector store unavailable).");
    }
}
