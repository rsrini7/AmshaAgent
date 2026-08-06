package com.amsha.core.routing.provider;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.routing.ModelDefaults;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Static {@link LlmProviderOnboardingAdapter} backed by {@code provider-catalog.json} on the
 * classpath. This replaces the raw catalog passthrough previously served by the onboarding resource:
 * provider/model suggestions now flow through the typed descriptor API, and the same adapter
 * performs tiered local validation of an onboarding connection selection.
 */
@ApplicationScoped
public class StaticProviderCatalogAdapter implements LlmProviderOnboardingAdapter {

    private static final Logger log = Logger.getLogger(StaticProviderCatalogAdapter.class);
    private static final String CATALOG_RESOURCE = "provider-catalog.json";

    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private volatile List<LlmProviderDescriptor> descriptors;

    @Override
    public List<LlmProviderDescriptor> descriptors() {
        if (descriptors == null) {
            descriptors = load();
        }
        return descriptors;
    }

    private List<LlmProviderDescriptor> load() {
        try (InputStream is = StaticProviderCatalogAdapter.class.getResourceAsStream("/" + CATALOG_RESOURCE)) {
            if (is == null) {
                log.errorf("Provider catalog resource %s not found on classpath", CATALOG_RESOURCE);
                return List.of();
            }
            List<RawProvider> raw = mapper.readValue(is, new TypeReference<List<RawProvider>>() {});
            List<LlmProviderDescriptor> result = new ArrayList<>();
            for (RawProvider r : raw) {
                try {
                    result.add(toDescriptor(r));
                } catch (IllegalArgumentException e) {
                    log.warnf("Skipping invalid provider catalog entry %s: %s", r.id, e.getMessage());
                }
            }
            return List.copyOf(result);
        } catch (Exception e) {
            log.errorf("Failed to load provider catalog: %s", e.toString());
            return List.of();
        }
    }

    private LlmProviderDescriptor toDescriptor(RawProvider r) {
        List<ModelEntry> catalog = new ArrayList<>();
        if (r.defaultModel != null && !r.defaultModel.isBlank()) {
            catalog.add(new ModelEntry(r.defaultModel, r.defaultModel));
        }
        return new LlmProviderDescriptor(
                r.id,
                r.name,
                r.id,
                r.requiresApiKey ? "API_KEY" : "NONE",
                ModelDefaults.defaultBaseUrlForProvider(r.id),
                Boolean.TRUE.equals(r.requiresApiKey),
                Boolean.TRUE.equals(r.embeddingCapable),
                catalog,
                Map.of(
                        "tag", r.tag == null ? "" : r.tag,
                        "icon", r.icon == null ? "" : r.icon,
                        "color", r.color == null ? "" : r.color,
                        "hasBaseUrl", String.valueOf(Boolean.TRUE.equals(r.hasBaseUrl))));
    }

    @Override
    public ProviderValidationResult validate(ConnectionValidationRequest request) {
        List<String> errors = new ArrayList<>();
        Optional<LlmProviderDescriptor> provider = descriptor(request.providerId());
        if (provider.isEmpty()) {
            errors.add("Unknown provider: " + request.providerId());
            return ProviderValidationResult.rejected(errors);
        }
        LlmProviderDescriptor p = provider.get();
        if (p.requiresApiKey() && !request.apiKeySupplied()) {
            errors.add("Provider " + p.id() + " requires an API key");
        }
        if (!request.modelId().isBlank() && !p.supportsModel(request.modelId())) {
            errors.add("Provider " + p.id() + " does not advertise model: " + request.modelId());
        }
        // Tiered egress check: a custom base URL must target an allowed host when an allow-list is set.
        if (!request.customBaseUrl().isBlank()) {
            String host = hostOf(request.customBaseUrl());
            if (host == null) {
                errors.add("Custom base URL must be an absolute HTTP(S) URL with a valid host");
            } else if (!request.allowedEgressHosts().isEmpty()
                    && request.allowedEgressHosts().stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
                errors.add("Custom base URL host is not permitted by the connection egress policy: " + host);
            }
        }
        return errors.isEmpty() ? ProviderValidationResult.ok() : ProviderValidationResult.rejected(errors);
    }

    private static String hostOf(String url) {
        try {
            URI uri = new URI(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return null;
            }
            return uri.getHost();
        } catch (Exception _) {
            return null;
        }
    }

    /** Jackson binding for the flat {@code provider-catalog.json} shape. */
    private static final class RawProvider {
        public String id;
        public String name;
        public String tag;
        public String icon;
        public String color;
        public String defaultModel;
        public Boolean embeddingCapable;
        public Boolean requiresApiKey;
        public Boolean hasBaseUrl;
    }
}
