package com.arcclaw.rag;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.reflex.PiiScanReflex;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.IdempotencyMode;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.persistence.api.IngestResult;
import com.arcclaw.persistence.api.MemoryEntryDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

@ApplicationScoped
@ToolMetadata(
    capabilityId = "ingest_document",
    description = "Ingest a document into the RAG knowledge base",
    defaultRoles = {"coordinator", "synthesizer"}
)
public class IngestDocumentTool implements AgentTool {

    @Inject MemoryRetrievalPort memoryPort;
    @Inject AuditService auditService;
    @Inject PiiScanReflex piiReflex;

    private static final Pattern DASH_CLEANUP = Pattern.compile("-{2,}");
    private static final Pattern EQUALS_CLEANUP = Pattern.compile("={2,}");
    private static final Pattern STAR_CLEANUP = Pattern.compile("\\*{2,}");
    private static final Pattern CODE_BLOCK_CLEANUP = Pattern.compile("(?m)^```.*$");
    private static final Pattern HEADER_CLEANUP = Pattern.compile("(?m)^#{1,6}\\s+");
    private static final Pattern NEWLINE_CLEANUP = Pattern.compile("(?m)(\\n\\s*){3,}");
    private static final Pattern CONTROL_CHAR_CLEANUP = Pattern.compile("[\\p{Cntrl}&&[^\n\t]]");

    @Override
    public String name() {        return ToolCapability.INGEST_DOCUMENT.toolName();
    }

    @Override
    public String description() {
        return "Ingest a text document into the RAG knowledge base for future retrieval. " +
               "Use this when the user provides a document, code, or reference material " +
               "that should be stored and searchable in future queries. " +
               "Required param: 'text' (the document content). " +
               "For storing short key-value pairs (name, phone, etc), use the /store reflex instead.";
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.WRITE_MEMORY;
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
                "The full factual statement or document text to store, e.g. 'The user's name is Alice'. " +
                "Do NOT store just an entity name - store the complete fact as a standalone sentence.")
            .addStringProperty("label",
                "A descriptive short label or key for this information (e.g. 'user_name', 'project_overview').")
            .required("text")
            .build();
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String userId = ctx.userId();
        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403,
                "Security violation: tenant context not bound.");
        }

        String text = (String) params.get("text");
        if (text == null) text = (String) params.get("content");
        if (text == null) text = (String) params.get("value");
        if (text == null) text = (String) params.get("document");
        if (text == null) text = (String) params.get("doc");
        if (text == null) text = (String) params.get("data");
        
        if (text == null || text.isBlank()) {
            return ToolResponse.failure(400, "text parameter is required.");
        }

        // 🟢 Fix Bug 2: Resolve [REDACTED-*] placeholders from RAW_USER_GOAL
        text = resolveRedactedPlaceholders(ctx, text);

        text = sanitize(text);  // ← sanitize before blank re-check

        if (text.isBlank()) {
            return ToolResponse.failure(400,
                "text is empty after sanitization - nothing to ingest.");
        }

        String label = params.containsKey("label")
            ? (String) params.get("label") : "unlabeled";

        String sessionId = WorkspaceScopeHolder.getIfActive();
        if (sessionId == null) sessionId = "no-session";

        IngestResult result = memoryPort.index(new MemoryEntryDto(null, text, Map.of("label", label)));

        auditService.recordMandatory(
            ctx.sessionId(), userId, ctx.agentRole(),
            SecurityEventType.MEMORY_INGESTED,
            String.format("label=%s length=%d snippet=%s", 
                label, text.length(), (text.length() > 50 ? text.substring(0, 50) + "..." : text))
        );

        if (result.isGlobalFallback()) {
            return ToolResponse.success(
                "Document ingested into SESSION memory only (Global KB is currently offline). chunkId: " + result.id());
        }

        return ToolResponse.success(
            "Document ingested successfully into Global KB. chunkId: " + result.id());
    }

    private String resolveRedactedPlaceholders(ToolExecutionContext ctx, String text) {
        if (text == null || !text.contains("[REDACTED-")) {
            return text;
        }

        String rawGoal = ctx.rawUserGoal();
        if (rawGoal == null) {
            return text;
        }

        // We use the PII scanner to find what it redacted in the original goal
        // This is a bit brute force but safe: we want to find the first match
        // in rawGoal for each redaction tag present in 'text'.
        String resolved = text;
        
        // Match [REDACTED-TYPE]
        Pattern tagPattern = Pattern.compile("\\[REDACTED-([A-Z]+)\\]");
        Matcher tagMatcher = tagPattern.matcher(text);
        
        while (tagMatcher.find()) {
            String fullTag = tagMatcher.group(0); // e.g. [REDACTED-PHONE]
            String type = tagMatcher.group(1);    // e.g. PHONE
            
            // Find the original value in rawGoal using the same PII logic
            String originalValue = piiReflex.extractFirstValue(rawGoal, type);
            if (originalValue != null) {
                resolved = resolved.replace(fullTag, originalValue);
            }
        }

        return resolved;
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
     * This also produces cleaner embedding vectors - stripped text
     * embeds on semantic content, not formatting noise.
     */
    private String sanitize(String raw) {
        if (raw == null) return null;
        String text = DASH_CLEANUP.matcher(raw).replaceAll("-");
        text = EQUALS_CLEANUP.matcher(text).replaceAll("");
        text = STAR_CLEANUP.matcher(text).replaceAll("");
        text = CODE_BLOCK_CLEANUP.matcher(text).replaceAll("");
        text = HEADER_CLEANUP.matcher(text).replaceAll("");
        text = NEWLINE_CLEANUP.matcher(text).replaceAll("\n\n");
        text = CONTROL_CHAR_CLEANUP.matcher(text).replaceAll("");
        return text.strip();
    }
}
