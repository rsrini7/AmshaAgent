package com.amsha.onboarding.extension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

/** Onboarding extension for optional HTTP/SSE MCP connections. */
@ApplicationScoped
public class McpOnboardingExtension implements OnboardingExtension {

    private static final Logger log = Logger.getLogger(McpOnboardingExtension.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EXTENSION_ID = "mcp";
    private static final String EXTENSION_VERSION = "1";

    @Inject
    McpConnectionLifecycleService lifecycleService;

    public void setLifecycleService(McpConnectionLifecycleService service) {
        this.lifecycleService = service;
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
        String fingerprint = context.ownerId() + ":" + context.extensionNamespace();
        try {
            Map<String, String> safe = Map.of("name", context.extensionNamespace(), "ownerId", context.ownerId());
            return new PreparedOnboardingStep(
                    EXTENSION_ID, EXTENSION_VERSION, fingerprint, MAPPER.writeValueAsBytes(safe));
        } catch (Exception _) {
            return new PreparedOnboardingStep(EXTENSION_ID, EXTENSION_VERSION, fingerprint, new byte[0]);
        }
    }

    @Override
    public OnboardingValidationResult validate(
            OnboardingContext context, PreparedOnboardingStep prepared, OnboardingInput input) {
        for (McpInput server : parseAll(input)) {
            if (server.name().isBlank()) return OnboardingValidationResult.fail("name is required");
            if (server.name().contains(",")) {
                return OnboardingValidationResult.fail("name cannot contain a comma");
            }
            if (server.endpoint().isBlank()) {
                return OnboardingValidationResult.fail("endpoint is required");
            }
            try {
                ToolCategory.valueOf(server.category());
            } catch (IllegalArgumentException _) {
                return OnboardingValidationResult.fail(
                        "category must be one of: " + Arrays.toString(ToolCategory.values()));
            }
            try {
                lifecycleService.validateHttpRegistration(server.endpoint(), server.headers());
            } catch (IllegalArgumentException e) {
                return OnboardingValidationResult.fail(e.getMessage());
            }
        }
        return OnboardingValidationResult.ok();
    }

    @Override
    public CompensatableOnboardingCommit plan(
            OnboardingContext context, PreparedOnboardingStep prepared, ValidatedOnboardingInput input) {
        return new McpOnboardingCommit(lifecycleService, context.ownerId(), parseAll(input.input()));
    }

    private static List<McpInput> parseAll(OnboardingInput input) {
        try {
            Map<String, Object> root =
                    MAPPER.readValue(input.jsonPayload(), new TypeReference<Map<String, Object>>() {});
            Object rawServers = root.get("servers");
            if (rawServers instanceof List<?> serverList) {
                List<McpInput> result = new ArrayList<>();
                for (Object rawServer : serverList) {
                    if (rawServer instanceof Map<?, ?> server) result.add(parseMap(server));
                }
                return List.copyOf(result);
            }
            return root.isEmpty() ? List.of() : List.of(parseMap(root));
        } catch (Exception e) {
            log.debugf("MCP onboarding input parse failed: %s", e.toString());
            return List.of(new McpInput("", "", ToolCategory.INTERNAL.name(), Map.of()));
        }
    }

    private static McpInput parseMap(Map<?, ?> map) {
        Map<String, String> headers = new LinkedHashMap<>();
        Object rawHeaders = map.get("headers");
        if (rawHeaders instanceof Map<?, ?> rawMap) {
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                    headers.put(key, value);
                }
            }
        }
        return new McpInput(
                stringValue(map.get("name")),
                stringValue(map.get("endpoint")),
                stringValueOr(map.get("category"), ToolCategory.INTERNAL.name()),
                Map.copyOf(headers));
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : "";
    }

    private static String stringValueOr(Object value, String fallback) {
        String result = stringValue(value);
        return result.isBlank() ? fallback : result;
    }

    private record McpInput(String name, String endpoint, String category, Map<String, String> headers) {}

    private static final class McpOnboardingCommit implements CompensatableOnboardingCommit {
        private final McpConnectionLifecycleService lifecycleService;
        private final String ownerId;
        private final List<McpInput> servers;

        private McpOnboardingCommit(
                McpConnectionLifecycleService lifecycleService, String ownerId, List<McpInput> servers) {
            this.lifecycleService = lifecycleService;
            this.ownerId = ownerId;
            this.servers = List.copyOf(servers);
        }

        @Override
        public String commit(String operationId) {
            List<String> committed = new ArrayList<>();
            try {
                for (McpInput server : servers) {
                    lifecycleService.persistSuccessfulHttp(
                            server.name(),
                            server.endpoint(),
                            ToolCategory.valueOf(server.category()),
                            ownerId,
                            server.headers());
                    committed.add(server.name());
                }
                return String.join(",", committed);
            } catch (RuntimeException e) {
                for (String name : committed) lifecycleService.deleteByNameAndUserId(name, ownerId);
                throw e;
            }
        }

        @Override
        public void compensate(String operationId, String receipt) {
            if (receipt == null || receipt.isBlank()) return;
            for (String name : receipt.split(",")) {
                lifecycleService.deleteByNameAndUserId(name, ownerId);
            }
        }
    }
}
