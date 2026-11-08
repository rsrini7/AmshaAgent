package com.amsha.core.tool.attempt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.amsha.persistence.api.ToolAttemptFingerprintFormat;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Code-owned resolver for trusted target identity per Proposal §4.2 and §12.2.
 * <p>
 * Keyed by {@link ToolCapability#toolName()}. The model cannot choose a lock scope or provide a target
 * fingerprint. For tools in the code-owned table, this resolver returns one unambiguous resource type and
 * canonical resource identity, then derives {@code targetFingerprint} as {@code target:sha256:v1:} over an
 * RFC 8785 canonical JSON envelope containing a domain tag, workspace ID, session ID, resource type, and
 * canonical resource identity.
 * <p>
 * Tools absent from the table, or any missing, ambiguous, malformed, or failed resolution, select
 * {@code SESSION_MUTATIONS} and a null {@code targetFingerprint}. It never silently disables the lock.
 */
@ApplicationScoped
public class ToolTargetResolver {

    private static final Logger log = Logger.getLogger(ToolTargetResolver.class);

    public static final String TARGET_FINGERPRINT_VERSION = ToolAttemptFingerprintFormat.TARGET_FINGERPRINT_VERSION;
    public static final String TARGET_PREFIX = ToolAttemptFingerprintFormat.TARGET_V1_PREFIX;
    public static final String DOMAIN_TAG = "target";

    private final ObjectMapper objectMapper;
    private final Map<String, TargetExtractor> extractors;

    @FunctionalInterface
    public interface TargetExtractor {
        Optional<TargetResource> extract(JsonNode parameters);
    }

    public record TargetResource(String resourceType, String canonicalResourceIdentity) {
        public TargetResource {
            if (resourceType == null || resourceType.isBlank()) {
                throw new IllegalArgumentException("resourceType must not be null or blank");
            }
            if (canonicalResourceIdentity == null || canonicalResourceIdentity.isBlank()) {
                throw new IllegalArgumentException("canonicalResourceIdentity must not be null or blank");
            }
        }
    }

    private final String workspaceRootDir;
    private final MeterRegistry meterRegistry;

    public ToolTargetResolver() {
        this(new ObjectMapper());
    }

    public ToolTargetResolver(ObjectMapper objectMapper) {
        this(objectMapper, "./data/workspaces");
    }

    public ToolTargetResolver(
            ObjectMapper objectMapper,
            @ConfigProperty(name = "amsha.workspace.root-dir", defaultValue = "./data/workspaces")
                    String workspaceRootDir) {
        this(objectMapper, workspaceRootDir, null);
    }

    @Inject
    public ToolTargetResolver(
            ObjectMapper objectMapper,
            @ConfigProperty(name = "amsha.workspace.root-dir", defaultValue = "./data/workspaces")
                    String workspaceRootDir,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.workspaceRootDir =
                (workspaceRootDir != null && !workspaceRootDir.isBlank()) ? workspaceRootDir : "./data/workspaces";
        this.meterRegistry = meterRegistry;
        this.extractors = Collections.unmodifiableMap(buildExtractors());
    }

    /**
     * Resolves the trusted target identity for a tool invocation with Map parameters.
     *
     * @param workspaceId non-blank workspace ID
     * @param sessionId non-blank session ID
     * @param toolName non-blank tool name
     * @param parameters tool parameters Map (may be null)
     * @return trusted {@link ToolTargetResolution}
     */
    public ToolTargetResolution resolve(
            String workspaceId, String sessionId, String toolName, Map<String, Object> parameters) {
        JsonNode node = parameters != null ? CanonicalJsonUtil.normalize(parameters) : null;
        return resolve(workspaceId, sessionId, toolName, node);
    }

    /**
     * Resolves the trusted target identity for a tool invocation with JsonNode parameters.
     *
     * @param workspaceId non-blank workspace ID
     * @param sessionId non-blank session ID
     * @param toolName non-blank tool name
     * @param parameters tool parameters JSON node (may be null)
     * @return trusted {@link ToolTargetResolution}
     */
    public ToolTargetResolution resolve(String workspaceId, String sessionId, String toolName, JsonNode parameters) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be null or blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be null or blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be null or blank");
        }

        TargetExtractor extractor = extractors.get(toolName);
        if (extractor == null || parameters == null || parameters.isNull() || !parameters.isObject()) {
            return fallback(toolName);
        }

        try {
            Optional<TargetResource> optTarget = extractor.extract(parameters);
            if (optTarget.isEmpty()) {
                return fallback(toolName);
            }

            TargetResource target = optTarget.get();
            String targetFingerprint = computeTargetFingerprint(
                    workspaceId, sessionId, target.resourceType(), target.canonicalResourceIdentity());

            return ToolTargetResolution.targetResource(
                    targetFingerprint, target.resourceType(), target.canonicalResourceIdentity());
        } catch (Exception _) {
            // Fail closed to SESSION_MUTATIONS on any resolution failure
            return fallback(toolName);
        }
    }

    private ToolTargetResolution fallback(String toolName) {
        if (meterRegistry != null) {
            meterRegistry
                    .counter("amsha.tool.target.fallback", "tool", metricLabel(toolName))
                    .increment();
        }
        log.debugf(
                "Tool target resolution fell back to SESSION_MUTATIONS for tool '%s'",
                sanitizeToolNameForLog(toolName));
        return ToolTargetResolution.sessionMutations();
    }

    private static String metricLabel(String toolName) {
        return ToolCapability.fromToolName(toolName)
                .map(ToolCapability::toolName)
                .orElse("unresolved");
    }

    private static String sanitizeToolNameForLog(String toolName) {
        if (toolName == null) {
            return "null";
        }
        String sanitized = toolName.replaceAll("[\\p{C}\\s]+", "_");
        if (sanitized.length() > 64) {
            return sanitized.substring(0, 64);
        }
        return sanitized;
    }

    /**
     * Computes the RFC 8785 canonical digest for a target resource.
     */
    public String computeTargetFingerprint(
            String workspaceId, String sessionId, String resourceType, String canonicalResourceIdentity) {
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("domain", DOMAIN_TAG);
        envelope.put("workspaceId", workspaceId);
        envelope.put("sessionId", sessionId);
        envelope.put("resourceType", resourceType);
        envelope.put("resourceIdentity", canonicalResourceIdentity);

        byte[] canonicalBytes = CanonicalJsonUtil.canonicalizeNodeToUtf8(envelope, this.objectMapper);
        return TARGET_PREFIX + CanonicalJsonUtil.sha256Hex(canonicalBytes);
    }

    private static final Set<String> AGENT_TASK_TARGETED_ACTIONS = Set.of("get", "transition", "update", "delete");

    private Map<String, TargetExtractor> buildExtractors() {
        Map<String, TargetExtractor> map = new HashMap<>();

        // Core memory tools addressing the same block produce the same resource type and identity (preserving case)
        TargetExtractor coreMemoryExtractor = params -> {
            String canonicalBlockKey = extractStringProperty(params, "block_key");
            if (canonicalBlockKey == null || !isValidResourceIdentity(canonicalBlockKey)) {
                return Optional.empty();
            }
            String trimmed = canonicalBlockKey.trim();

            // An alias that is present but malformed is ambiguous and must fail closed.
            if (!aliasMatches(params, "block", trimmed, value -> normalizeResourceIdentity(value))) {
                return Optional.empty();
            }

            return Optional.of(new TargetResource("core_memory", trimmed));
        };

        map.put(ToolCapability.CORE_MEMORY_APPEND.toolName(), coreMemoryExtractor);
        map.put(ToolCapability.CORE_MEMORY_REPLACE.toolName(), coreMemoryExtractor);
        map.put(ToolCapability.CORE_MEMORY_READ.toolName(), coreMemoryExtractor);

        // Agent task tool (preserving case, action-aware per AgentTaskTool executor contract)
        map.put(ToolCapability.AGENT_TASK.toolName(), params -> {
            String action = extractStringProperty(params, "action");
            if (action == null || action.isBlank()) {
                return Optional.empty();
            }
            String normalizedAction = action.trim().toLowerCase(Locale.ROOT);
            if (!AGENT_TASK_TARGETED_ACTIONS.contains(normalizedAction)) {
                return Optional.empty();
            }

            // Executor's canonical parameter is "taskId"
            String canonicalTaskId = extractStringProperty(params, "taskId");
            if (canonicalTaskId == null || !isValidTaskIdentity(canonicalTaskId)) {
                return Optional.empty();
            }
            String canonicalTaskIdValue = canonicalTaskIdentity(canonicalTaskId);

            // An alias that is present but malformed or different is ambiguous and fails closed.
            if (!aliasMatches(params, "task_id", canonicalTaskIdValue, this::normalizeTaskIdentity)) {
                return Optional.empty();
            }
            if (!aliasMatches(params, "path", canonicalTaskIdValue, this::normalizeTaskIdentity)) {
                return Optional.empty();
            }

            return Optional.of(new TargetResource("agent_task", canonicalTaskIdValue));
        });

        // Schedule management tool (preserving case, action-aware per ManageScheduleTool executor contract)
        map.put(ToolCapability.MANAGE_SCHEDULE.toolName(), params -> {
            String action = extractStringProperty(params, "action");
            if (action == null || action.isBlank()) {
                return Optional.empty();
            }
            if (!"delete".equalsIgnoreCase(action.trim())) {
                return Optional.empty();
            }

            // Executor's canonical parameter is "cronId"
            String canonicalCronId = extractStringProperty(params, "cronId");
            if (canonicalCronId == null || !isValidResourceIdentity(canonicalCronId)) {
                return Optional.empty();
            }
            String trimmedCronId = canonicalCronId.trim();

            // An alias that is present but malformed or different is ambiguous and fails closed.
            if (!aliasMatches(params, "cron_id", trimmedCronId, ToolTargetResolver::normalizeResourceIdentity)) {
                return Optional.empty();
            }
            if (!aliasMatches(params, "taskId", trimmedCronId, ToolTargetResolver::normalizeResourceIdentity)) {
                return Optional.empty();
            }

            return Optional.of(new TargetResource("schedule", trimmedCronId));
        });

        return map;
    }

    private Path getTasksRoot() {
        return Path.of(workspaceRootDir).resolve("tasks").toAbsolutePath().normalize();
    }

    private boolean isValidTaskIdentity(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        for (int i = 0; i < taskId.length(); i++) {
            char c = taskId.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        try {
            Path candidate = Path.of(taskId.trim());
            // FileSystemTaskRepository emits absolute IDs and findById() passes the ID
            // directly to Path.of(). Resolving a relative ID here against tasksRoot would
            // fingerprint a different path from the one the executor actually accesses.
            if (!candidate.isAbsolute()) {
                return false;
            }
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!normalized.toString().endsWith(".md")) {
                return false;
            }
            Path tasksRoot = getTasksRoot();
            return normalized.startsWith(tasksRoot)
                    && !normalized.equals(tasksRoot)
                    && !containsSymlinkBelowTasksRoot(normalized, tasksRoot);
        } catch (Exception _) {
            return false;
        }
    }

    private static boolean containsSymlinkBelowTasksRoot(Path taskPath, Path tasksRoot) {
        Path current = tasksRoot;
        for (Path component : tasksRoot.relativize(taskPath)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private String canonicalTaskIdentity(String taskId) {
        return Path.of(taskId.trim()).toAbsolutePath().normalize().toString();
    }

    private Optional<String> normalizeTaskIdentity(String taskId) {
        return isValidTaskIdentity(taskId) ? Optional.of(canonicalTaskIdentity(taskId)) : Optional.empty();
    }

    private static Optional<String> normalizeResourceIdentity(String identity) {
        return isValidResourceIdentity(identity) ? Optional.of(identity.trim()) : Optional.empty();
    }

    private static boolean aliasMatches(
            JsonNode parameters,
            String aliasName,
            String canonicalValue,
            Function<String, Optional<String>> normalizer) {
        if (!parameters.has(aliasName)) {
            return true;
        }
        JsonNode alias = parameters.get(aliasName);
        if (alias == null || !alias.isTextual() || alias.asText().isBlank()) {
            return false;
        }
        return normalizer.apply(alias.asText()).filter(canonicalValue::equals).isPresent();
    }

    private static boolean isValidResourceIdentity(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        return !id.contains("/") && !id.contains("\\") && !id.contains("..");
    }

    private static String extractStringProperty(JsonNode params, String propertyName) {
        if (params == null || !params.isObject()) {
            return null;
        }
        JsonNode val = params.get(propertyName);
        if (val != null && val.isTextual() && !val.asText().isBlank()) {
            return val.asText();
        }
        return null;
    }
}
