package com.amsha.onboarding.extension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.amsha.core.tools.ToolCategory;
import com.amsha.mcp.transport.McpConnectionLifecycleService;
import com.amsha.onboarding.spi.CompensatableOnboardingCommit;
import com.amsha.onboarding.spi.OnboardingContext;
import com.amsha.onboarding.spi.OnboardingExtension;
import com.amsha.onboarding.spi.OnboardingFieldDescriptor;
import com.amsha.onboarding.spi.OnboardingFieldType;
import com.amsha.onboarding.spi.OnboardingInput;
import com.amsha.onboarding.spi.OnboardingStepDescriptor;
import com.amsha.onboarding.spi.OnboardingValidationResult;
import com.amsha.onboarding.spi.PreparedOnboardingStep;
import com.amsha.onboarding.spi.ValidatedOnboardingInput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Onboarding extension that contributes an optional MCP server connection stage to the shared draft
 * saga. It implements the same {@link OnboardingExtension} contract used by every onboarding stage,
 * so MCP configuration flows through the coordinator's plan/fingerprint/revision/audit machinery and
 * is committed or compensated like any other stage.
 *
 * <p>Secrets (MCP auth headers) are handled by the lifecycle service's vault-backed storage, never
 * placed in the non-secret {@link OnboardingInput} payload. This slice commits a connection from
 * non-secret fields (name, endpoint, category) and defers header secrets to the staged secret handle
 * wired by the coordinator.</p>
 */
@ApplicationScoped
public class McpOnboardingExtension implements OnboardingExtension {

    private static final Logger log = Logger.getLogger(McpOnboardingExtension.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String EXTENSION_ID = "mcp";
    static final String EXTENSION_VERSION = "1.0.0";

    @Inject
    McpConnectionLifecycleService lifecycleService;

    /** For testing / direct construction. */
    public void setLifecycleService(McpConnectionLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Override
    public OnboardingStepDescriptor descriptor() {
        List<OnboardingFieldDescriptor> fields = List.of(
                new OnboardingFieldDescriptor(
                        "name",
                        OnboardingFieldType.TEXT,
                        true,
                        List.of(),
                        "onboarding.mcp.name.title",
                        "onboarding.mcp.name.desc"),
                new OnboardingFieldDescriptor(
                        "endpoint",
                        OnboardingFieldType.URL,
                        true,
                        List.of(),
                        "onboarding.mcp.endpoint.title",
                        "onboarding.mcp.endpoint.desc"),
                new OnboardingFieldDescriptor(
                        "category",
                        OnboardingFieldType.SELECT,
                        true,
                        Arrays.stream(ToolCategory.values()).map(Enum::name).toList(),
                        "onboarding.mcp.category.title",
                        "onboarding.mcp.category.desc"));
        return new OnboardingStepDescriptor(
                EXTENSION_ID, EXTENSION_VERSION, 90, true, fields, "onboarding.mcp.title", "onboarding.mcp.desc");
    }

    @Override
    public PreparedOnboardingStep prepare(OnboardingContext context) {
        // safeData carries only non-secret selections; fingerprint is stable for idempotency.
        Map<String, String> safe = Map.of(
                "name", context.extensionNamespace(),
                "ownerId", context.ownerId());
        String fingerprint = context.ownerId() + ":" + context.extensionNamespace();
        try {
            return new PreparedOnboardingStep(
                    EXTENSION_ID, EXTENSION_VERSION, fingerprint, MAPPER.writeValueAsBytes(safe));
        } catch (Exception _) {
            return new PreparedOnboardingStep(EXTENSION_ID, EXTENSION_VERSION, fingerprint, new byte[0]);
        }
    }

    @Override
    public OnboardingValidationResult validate(
            OnboardingContext context, PreparedOnboardingStep prepared, OnboardingInput input) {
        McpInput parsed = parse(input);
        if (parsed.name().isBlank()) {
            return OnboardingValidationResult.fail("name is required");
        }
        if (parsed.endpoint().isBlank()) {
            return OnboardingValidationResult.fail("endpoint is required");
        }
        try {
            ToolCategory.valueOf(parsed.category());
        } catch (IllegalArgumentException _) {
            return OnboardingValidationResult.fail(
                    "category must be one of: " + Arrays.toString(ToolCategory.values()));
        }
        // Delegates endpoint/header validation to the lifecycle service (no secret storage here).
        try {
            lifecycleService.validateHttpRegistration(parsed.endpoint(), parsed.headers());
        } catch (IllegalArgumentException e) {
            return OnboardingValidationResult.fail(e.getMessage());
        }
        return OnboardingValidationResult.ok();
    }

    @Override
    public CompensatableOnboardingCommit plan(
            OnboardingContext context, PreparedOnboardingStep prepared, ValidatedOnboardingInput input) {
        McpInput parsed = parse(input.input());
        ToolCategory category = ToolCategory.valueOf(parsed.category());
        return new McpOnboardingCommit(
                lifecycleService, context.ownerId(), parsed.name(), parsed.endpoint(), category, parsed.headers());
    }

    private static McpInput parse(OnboardingInput input) {
        try {
            Map<String, Object> map =
                    MAPPER.readValue(input.jsonPayload(), new TypeReference<Map<String, Object>>() {});
            Map<String, String> headers = Map.of();
            Object rawHeaders = map.get("headers");
            if (rawHeaders instanceof Map<?, ?> m) {
                headers = m.entrySet().stream()
                        .filter(e -> e.getKey() instanceof String && e.getValue() instanceof String)
                        .collect(Collectors.toMap(
                                e -> (String) e.getKey(), e -> (String) e.getValue(), (a, b) -> b, LinkedHashMap::new));
            }
            return new McpInput(
                    String.valueOf(map.getOrDefault("name", "")),
                    String.valueOf(map.getOrDefault("endpoint", "")),
                    String.valueOf(map.getOrDefault("category", ToolCategory.INTERNAL.name())),
                    headers);
        } catch (Exception e) {
            log.debugf("MCP onboarding input parse failed: %s", e.toString());
            return new McpInput("", "", ToolCategory.INTERNAL.name(), Map.of());
        }
    }

    private record McpInput(String name, String endpoint, String category, Map<String, String> headers) {}

    /**
     * Idempotent commit that persists the MCP HTTP-SSE connection, with reverse-order compensation
     * that removes it. The receipt is the stable connection name so compensation is deterministic.
     * Auth headers (resolved from vault references by the coordinator) are passed to the lifecycle
     * service for vault-backed storage.
     */
    static final class McpOnboardingCommit implements CompensatableOnboardingCommit {
        private final McpConnectionLifecycleService lifecycleService;
        private final String ownerId;
        private final String name;
        private final String endpoint;
        private final ToolCategory category;
        private final Map<String, String> headers;

        McpOnboardingCommit(
                McpConnectionLifecycleService lifecycleService,
                String ownerId,
                String name,
                String endpoint,
                ToolCategory category,
                Map<String, String> headers) {
            this.lifecycleService = lifecycleService;
            this.ownerId = ownerId;
            this.name = name;
            this.endpoint = endpoint;
            this.category = category;
            this.headers = headers != null ? headers : Map.of();
        }

        @Override
        public String commit(String operationId) {
            // persistSuccessfulHttp upserts by (name, ownerId) → idempotent across retries/restarts.
            // Auth headers are passed for vault-backed storage by the lifecycle service.
            lifecycleService.persistSuccessfulHttp(name, endpoint, category, ownerId, headers);
            return name;
        }

        @Override
        public void compensate(String operationId, String receipt) {
            lifecycleService.deleteByNameAndUserId(receipt, ownerId);
        }
    }
}
